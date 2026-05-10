# Подписки и совместимость

## Обзор
`SubscriptionSource` - основная сущность для серверных списков, которые загружаются по URL. Обычные импортированные профили продолжают использовать тот же pipeline парсинга и runtime-подготовки.

Подписки обслуживают:
- `RoomSubscriptionRepository` - CRUD, загрузка, выбор лучшего ответа и безопасное сохранение дочерних профилей;
- `SubscriptionPayloadDecoder` - нормализация plain/base64 payload;
- `SubscriptionParser` - извлечение строк, HAPP Routing и переиспользование `ProfileImportParser`;
- `SubscriptionUpdateWorker` - периодическое обновление через WorkManager.

## HAPP Crypt5
Приложение поддерживает ссылки вида `happ://crypt5/...`.

Где применяется расшифровка:
- при добавлении подписки вручную;
- при импорте профиля;
- при открытии внешней ссылки через Android `VIEW`/`EDIT`;
- при импорте текста или файла через `SEND`/`SEND_MULTIPLE`.

После расшифровки payload обрабатывается по содержимому:
- `http://` или `https://` добавляется как URL-подписка;
- `vless://`, `vmess://`, `trojan://`, Xray JSON или AWG `.conf` импортируются как профили;
- список серверов разбирается как подписка или набор профилей.

Если payload пустой, поврежденный или ключ не найден, импорт завершается обычной ошибкой `IMPORT-001` или `SUBS-001`, в зависимости от точки входа.

## HAPP Routing
Поддерживаются routing-ссылки:
- `happ://routing/add/...`
- `happ://routing/onadd/...`

Routing может прийти:
- отдельной строкой в теле подписки;
- внутри общего payload;
- в HTTP-заголовке `routing`.

`HappRoutingCompat` декодирует routing JSON и переносит в Xray:
- `DomainStrategy` -> `routing.domainStrategy`;
- `BlockSites` / `BlockIp` -> rule с `outboundTag=block`;
- `DirectSites` / `DirectIp` -> rule с `outboundTag=direct`;
- `ProxySites` / `ProxyIp` -> rule с `outboundTag=proxy`;
- `RemoteDNSIP`, `DomesticDNSIP`, `RemoteDns`, `DomesticDns` -> `dns.servers`;
- `DnsHosts` -> `dns.hosts`.

Routing-ссылка сама по себе не сохраняется как сервер. Если routing найден рядом с профилями, он применяется ко всем импортируемым Xray-профилям. AmneziaWG-профили routing-правилами HAPP не изменяются.

## HWID-Gated Compatibility
Некоторые Marzban/HAPP-style провайдеры отдают полезную подписку только для распознанного клиента. Если клиент не распознан, сервер может вернуть marker payload, например `0.0.0.0:1`, zero UUID или текст вроде `app not supported`.

Поддерживаемые режимы клиента:
- `auto` - режим по умолчанию;
- `generic`;
- `marzban-hwid`;
- `happ`.

Режим хранится в `SubscriptionSource.metadata.clientMode` и сохраняется между обновлениями.

## Request Headers
Совместимые запросы могут отправлять:
- `x-hwid`;
- `device-os`;
- `device-model`;
- `x-device-os`;
- `x-device-model`.

User-Agent:
- generic/hwid: `PrivateVPN-Android/1.0`;
- happ: `Happ/1.0`.

## HWID
HWID стабилен в рамках установки приложения:
1. репозиторий читает `SharedPreferences` ключ `subscription_compat.compat_hwid`;
2. если значения нет, строит SHA-256 из `packageName + ANDROID_ID` и сохраняет 32 символа;
3. сохраненный HWID переиспользуется для следующих compatibility-запросов.

Это исключает случайный идентификатор на каждый refresh.

## Endpoint Strategy
Загрузка подписки поддерживает:
- исходный endpoint, например `/sub/{token}`;
- client-type endpoint, например `/sub/{token}/{client_type}` для `happ`.

В `auto` режиме:
1. первый запрос идет на исходный endpoint;
2. если ответ похож на compatibility gate, выполняются retry-кандидаты:
   - исходный endpoint + HWID;
   - исходный endpoint с HAPP headers;
   - `/happ` endpoint + HAPP headers;
3. лучший ответ выбирается по числу connectable профилей, а при равенстве - по меньшему числу marker-записей.

Дополнительно включен probe для VLESS/REALITY, где сервер вернул профили без `shortId`: приложение пробует HAPP/HWID варианты, но больше не считает пустой `shortId` фатальной ошибкой.

## Расширенная Совместимость Конфигов
VLESS parser принимает разные варианты названий параметров, которые встречаются у провайдеров:
- public key: `pbk`, `publicKey`, `publickey`, `password`;
- short id: `sid`, `shortId`, `shortid`, `short_id`;
- server name: `sni`, `serverName`, `servername`, `server_name`;
- fingerprint: `fp`, `fingerprint`;
- spiderX: `spx`, `spiderX`, `spiderx`, `spider_x`;
- host: `host`, `authority`;
- service name: `serviceName`, `servicename`, `service_name`, `service`.

Поддерживаемые параметры транспорта:
- `path`;
- `host` / `authority`;
- `alpn`;
- `allowInsecure`;
- `serviceName`;
- `mode`;
- `headerType`.

Поддерживаемые network значения:
- `tcp` / `raw`;
- `ws` / `websocket`;
- `grpc`;
- `h2` / `http`;
- `httpupgrade`;
- `splithttp`;
- `xhttp`.

Runtime-подготовка Xray дополнительно нормализует JSON-конфиги:
- переносит REALITY aliases в canonical поля `publicKey`, `shortId`, `serverName`, `fingerprint`, `spiderX`;
- принимает `shortIds` / `shortids` и берет первое непустое значение;
- при отсутствии `shortId` выставляет пустую строку для серверов, где empty shortId разрешен;
- при отсутствии `serverName` использует address первого `vnext`;
- при отсутствии `fingerprint` применяет `chrome`;
- при отсутствии `spiderX` применяет пустую строку;
- сохраняет совместимые DNS-поля `hosts`, `queryStrategy`, `disableCache` при пересборке `dns`.

## Provider Compatibility Detection
Compatibility gate определяется по совокупности сигналов:
- HTTP-заголовки `X-Hwid-Active`, `X-Hwid-Not-Supported`;
- marker endpoints `0.0.0.0:1` и zero UUID;
- фразы вроде `app not supported`, `install app`;
- platform/client hints, включая `happ`, `android`, `windows`, `v2rayng`, `nekobox`, `clash`, `sing-box`.

Если в заголовках есть provider metadata, она переносится в metadata подписки.

## Metadata Normalization
Поддерживаемые заголовки:
- `Profile-Title`;
- `Subscription-Userinfo` (`upload`, `download`, `total`, `expire`);
- `Support-Url`;
- `Profile-Web-Page-Url`;
- `Announce`;
- `Flclashx-Servicename`;
- `Flclashx-Servicelogo`;
- provider hints: `Provider-*`, `Service-*`, `Tag*`, `Plan-Id`, `User-Id`, `Badge`, `Note`.

Вычисляемые поля:
- used traffic = `upload + download`;
- remaining traffic = `total - used`, если известен `total`;
- человекочитаемый expiry из `expire`;
- preferred external link: provider site, profile web page, затем support URL.

Diagnostics сохраняет:
- полученные metadata headers;
- краткое описание извлеченной metadata;
- проигнорированные metadata headers.

## Parsing and Safe Persist
Pipeline обновления:
1. загрузить payload;
2. декодировать plain/base64;
3. добавить routing из HTTP-заголовка, если он есть;
4. извлечь profile entries и HAPP Routing;
5. распарсить entries через `ProfileImportParser`;
6. применить HAPP Routing к Xray-профилям;
7. разделить `marker` и `connectable`;
8. атомарно заменить дочерние профили только если есть валидная connectable часть.

Если refresh падает или provider вернул только markers, старые рабочие профили не удаляются.

## Diagnostics
Диагностика подписки включает:
- compatibility mode;
- selected endpoint и endpoint strategy;
- client type;
- `hwidActive` / `hwidNotSupported`;
- `retryWithHwid`;
- marker/connectable/saved counts;
- detected format;
- HTTP status;
- краткую ошибку;
- trace по `shortId` на этапах parse, persist и runtime prepare.

Эти данные доступны в деталях подписки и логах.

## UI и Внешний Импорт
Приложение регистрирует обработчики Android intents:
- `VIEW` / `EDIT` для схем `happ`, `vless`, `vmess`, `trojan`;
- `VIEW` / `EDIT` для `content://` и `file://`;
- `SEND` / `SEND_MULTIPLE` для текста и файлов.

Внешний импорт умеет отличать ссылку подписки от одиночного профиля. После успешного импорта приложение переводит пользователя на экран профилей.

## End-to-End Invariants
После refresh репозиторий гарантирует:
- выбранный ответ логируется явно: source, status, connectable, marker;
- parser handoff логируется с body signature и line count;
- persist логируется с inserted/updated/filtered/duplicate counts;
- marker-only записи от старых версий очищаются и не отображаются как серверы;
- если выбранный ответ содержит `connectable > 0`, именно эти профили становятся источником списков Home/Profiles.

## Reliability Notes
- ошибки refresh не стирают старые рабочие профили;
- частичный импорт сохраняет валидную часть;
- фоновые обновления выполняются через WorkManager и interval gating;
- активный профиль остается связан через `activeProfileId` и `lastSelectedProfileId`;
- empty `shortId` для REALITY допускается на уровне runtime, если серверная сторона это поддерживает.
