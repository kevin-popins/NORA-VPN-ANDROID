# NoraVPN Architecture

## 1. Общая архитектура

### 1.1 Слои приложения

- `UI (Compose)`:
  - экраны и навигация: `app/src/main/java/com/privatevpn/app/ui/`, `.../ui/screens/`, `.../navigation/`
  - отображение статуса VPN, ошибок, профилей, настроек и split tunneling
- `ViewModel`:
  - `AppViewModel` координирует бизнес-операции, состояние UI, события логов, profile selection и connect/disconnect
- `Domain/Application orchestration`:
  - выбор backend, backend switch, обработка recoverable/non-recoverable ошибок, ретраи
- `Repositories / Data`:
  - `RoomProfilesRepository`, `RoomSubscriptionRepository`, `DataStoreUserSettingsRepository`, `AndroidInstalledAppsRepository`
- `Backend adapters`:
  - `XrayBackendAdapter` (через `VpnController` + `PrivateVpnService`)
  - `AmneziaWgBackendAdapter` (через `GoBackend`)
- `VPN service / runtime`:
  - `PrivateVpnService` (Xray + tun2proxy data plane + TUN lifecycle)
  - `VpnRuntimeStateStore` (shared runtime state/status/error)
- `Parser/import`:
  - `ProfileImportParser`, `AmneziaWgConfigParser`
  - `SubscriptionParser`, `SubscriptionPayloadDecoder`, `HappCrypt5Decryptor`, `HappRoutingCompat`
- `Logging`:
  - event log в `AppViewModel`
  - runtime-level диагностика в `PrivateVpnService` и backend adapters

### 1.2 Поддержка нескольких протоколов

- Профиль хранит `ProfileType` (`VLESS/VMESS/TROJAN/XRAY_JSON/XRAY_VLESS_REALITY/AMNEZIA_WG_20`).
- Выбор backend:
  - `AMNEZIA_WG_20` -> `AmneziaWgBackendAdapter`
  - остальные типы -> `XrayBackendAdapter`
- В `AppViewModel` используется stateful switch с учетом:
  - текущего активного backend
  - последнего backend (`lastBackendProfileType`)
  - текущего runtime status (`READY/CONNECTING/CONNECTED/...`)

### 1.3 Профили

- Профили импортируются через `ProfileImportParser`.
- Хранятся в Room.
- Активный профиль хранится в DataStore (`activeProfileId`).
- При подключении используется активный профиль или первый доступный.
- Внешний импорт поддерживает Android `VIEW`/`EDIT`/`SEND`/`SEND_MULTIPLE` для ссылок `happ`, `vless`, `vmess`, `trojan`, HTTP(S), `content://` и `file://`.

### 1.3.1 Подписки и HAPP compatibility

- Подписки хранятся как `SubscriptionSource`, дочерние профили связаны через `parentSubscriptionId`.
- `happ://crypt5/...` расшифровывается до обычного URL, профиля или payload подписки до валидации и сохранения.
- `HappRoutingCompat` извлекает routing из тела подписки или HTTP-заголовка `routing` и применяет его к Xray-профилям.
- Compatibility refresh выбирает лучший ответ между base endpoint, HWID endpoint и HAPP endpoint по числу connectable профилей.
- Marker payload (`0.0.0.0:1`, zero UUID, app-not-supported ответы) не сохраняется как рабочий сервер.

### 1.4 Split tunneling / trusted apps

- Управляется `privateSessionEnabled` + `trustedPackages`.
- Для Xray применяется через `PrivateVpnService.Builder` (`addAllowedApplication` в режиме Private Session).
- Для AWG применяется через `AmneziaWgRuntimeConfigBuilder` (`IncludeApplications`).

### 1.5 SOCKS

- Пользовательский localhost SOCKS настраивается в settings.
- Для Xray data plane используется внутренний SOCKS (`Tun2ProxyDataPlane`).
- Для AWG backend пользовательский SOCKS не применяется автоматически runtime-ом (логируется в notes).

### 1.6 Notification / Quick Tile

- Foreground notification: `PrivateVpnService`.
- Quick tile: `VpnQuickSettingsTileService`.
- Ошибки tile теперь маппятся на кодированные `TILE-*`.

## 2. Жизненный цикл подключения

1. Пользователь выбирает профиль.
2. Нажимает `Подключить`.
3. `AppViewModel.connectVpn()`:
   - сериализует операцию через `backendOperationMutex`
   - валидирует permission/split/socks состояния
   - определяет целевой backend
4. Если нужен межпротокольный switch:
   - stop старого backend
   - ожидание готовности runtime
   - короткий settle delay
5. Старт целевого backend:
   - Xray: runtime config -> `VpnController` -> `PrivateVpnService`
   - AWG: `GoBackend.setState(UP, config)`
6. Статус -> `CONNECTED`, обновление tile/notification/UI.
7. Disconnect:
   - stop текущего backend
   - очищение runtime state
   - статус `READY` или `NO_PERMISSION`.

## 3. Backend Switch (Xray <-> AWG)

### 3.1 Предыдущая проблема

- Race при смене backend engine:
  - старый backend еще не полностью освобожден
  - новый backend стартует слишком рано
  - первый connect мог вернуть нативный `Unknown error`
  - второй connect срабатывал, когда teardown уже завершился

### 3.2 Что изменено

- Введен координируемый switch pipeline в `AppViewModel`:
  - `connectVpnInternal()`
  - `performBackendSwitch()`
  - `awaitBackendReadyStatus()`
  - `startBackendWithWarmupRetry()`
- Добавлены stage-логи backend switch:
  - старый тип backend
  - новый тип backend
  - этап остановки
  - этап ожидания готовности
  - факт готовности
- Добавлен авто-ретрай запуска после switch при transient warmup-ошибках.
- Добавлен `lastBackendProfileType`, чтобы switch корректно работал и после disconnect.

### 3.3 Защита от раннего connect

- Если connect приходит во время предыдущей backend-операции:
  - возвращается recoverable ошибка `BACKEND-001`
  - в лог пишется явная причина раннего вызова.

## 4. Система ошибок

- Введены `AppErrorCode`, `AppError`, `AppErrors`.
- В UI показывается короткое сообщение с кодом:
  - `Код ошибки: BACKEND-001. ...`
- В dev-логах сохраняется техническая причина:
  - домен, recoverable flag, raw reason.
- Ошибки разделены по доменам:
  - profile import
  - backend switch
  - xray runtime
  - awg runtime
  - socks/localhost
  - split tunneling
  - notification/tile
  - generic UI/state

Подробный каталог: `docs/error-codes.md`.

## 5. Совместимость Xray Config

Перед запуском Xray `XrayRuntimeConfigPreparer` делает runtime-нормализацию:
- пересобирает DNS, сохраняя `hosts`, `queryStrategy`, `disableCache`;
- нормализует REALITY aliases (`pbk/publickey`, `sid/shortid`, `sni/servername`, `fp`, `spx`);
- подставляет безопасные defaults для `shortId`, `serverName`, `fingerprint`, `spiderX`, когда провайдерский конфиг допускает неполную форму;
- переводит часть self-check по REALITY в мягкую диагностику, чтобы совместимые, но нетипичные конфиги не блокировались до запуска Xray.

Импорт VLESS-ссылок также поддерживает provider-specific transport параметры: `ws`, `grpc`, `h2/http`, `httpupgrade`, `splithttp`, `xhttp`, TLS `alpn/allowInsecure`, `host/authority`, `path`, `serviceName`, `mode`, `headerType`.
