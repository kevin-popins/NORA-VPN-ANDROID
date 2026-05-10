# NoraVPN Error Codes

Формат кода: `<DOMAIN>-<NNN>`, например `BACKEND-001`, `XRAY-101`.

## Каталог

| Код | Название | Описание | Где возникает | Что показать пользователю | Что писать в лог | Recoverable |
|---|---|---|---|---|---|---|
| `VPN-001` | VPN Permission Required | Нет системного разрешения VPN | `VpnController.connect`, `AppViewModel.connectVpnInternal` | `Код ошибки: VPN-001. Для подключения требуется разрешение VPN.` | `VpnService.prepare != null` + контекст вызова | Да |
| `IMPORT-001` | Profile Import Failed | Ошибка импорта профиля/файла | `AppViewModel.importProfileFromFile`, `importProfileInternal` | `Код ошибки: IMPORT-001. Не удалось импортировать профиль.` | Исходная parser/IO причина | Да |
| `IMPORT-002` | Profile Format Unsupported | Формат профиля не поддерживается | `AppViewModel.importProfileInternal` | `Код ошибки: IMPORT-002. Формат профиля не поддерживается.` | Raw parse reason (`Неподдерживаемый формат...`) | Да |
| `BACKEND-001` | Backend Switch In Progress | Connect вызван до готовности backend/switch ещё идет | `AppViewModel.connectVpn`/switch flow | `Код ошибки: BACKEND-001. Идёт переключение движка подключения...` | from->to backend, признак раннего вызова, статус | Да |
| `BACKEND-002` | Backend Switch Timeout | Не дождались готовности backend после stop | `AppViewModel.performBackendSwitch` | `Код ошибки: BACKEND-002. Переключение движка заняло слишком много времени...` | timeout + финальный runtime status | Да |
| `BACKEND-003` | Backend Switch Failed | Ошибка stop/start подготовки backend | `AppViewModel.performBackendSwitch`, `VpnController` fallback | `Код ошибки: BACKEND-003. Не удалось подготовить движок подключения.` | detailed stop/start exception | Да |
| `XRAY-101` | Xray Runtime Start Failed | Ошибка старта Xray runtime/data-plane | `PrivateVpnService.connectVpn`, `AppViewModel.mapBackendStartError` | `Код ошибки: XRAY-101. Не удалось запустить Xray runtime.` | backend/process/runtime-tail причина | Да |
| `XRAY-102` | Xray Runtime Stop Failed | Ошибка остановки Xray runtime | `PrivateVpnService.disconnectVpn`, `AppViewModel.disconnectVpnInternal` fallback | `Код ошибки: XRAY-102. Не удалось корректно остановить Xray runtime.` | исключение stop/cleanup | Да |
| `AWG-101` | AWG Runtime Start Failed | Ошибка старта AmneziaWG runtime | `AmneziaWgBackendAdapter.start`, `AppViewModel.mapBackendStartError` | `Код ошибки: AWG-101. Не удалось запустить AmneziaWG runtime.` | stack/cause из `GoBackend.setState` | Да |
| `AWG-102` | AWG Runtime Stop Failed | Ошибка остановки AmneziaWG runtime | `AmneziaWgBackendAdapter.stop`, `AppViewModel.disconnectVpnInternal` fallback | `Код ошибки: AWG-102. Не удалось корректно остановить AmneziaWG runtime.` | stop failure details | Да |
| `SOCKS-001` | Socks Invalid Port | Некорректный порт localhost SOCKS | `AppViewModel.saveSocksSettings` | `Код ошибки: SOCKS-001. Порт SOCKS должен быть в диапазоне 1-65535.` | invalid port value | Да |
| `SOCKS-002` | Socks Auth Required | Для localhost SOCKS отсутствуют логин/пароль | `AppViewModel.saveSocksSettings` | `Код ошибки: SOCKS-002. Для localhost SOCKS обязательны логин и пароль.` | missing credentials context | Да |
| `SPLIT-001` | Split Trusted Apps Empty | Включен split mode без trusted apps | `AppViewModel.connectVpnInternal` | `Код ошибки: SPLIT-001. В режиме приватной сессии выберите хотя бы одно доверенное приложение.` | privateSession enabled + empty trusted list | Да |
| `SPLIT-002` | Split Apply Failed | Ошибка применения split tunneling настроек | `AppViewModel` trusted apps/private session operations | `Код ошибки: SPLIT-002. Не удалось применить настройки раздельного туннелирования.` | repository/storage failure details | Да |
| `NOTIFY-001` | Notification Permission Required | Нет разрешения на уведомления | `AppViewModel.onNotificationPermissionResult` (deny path) | `Код ошибки: NOTIFY-001. Разрешите уведомления, чтобы видеть статус VPN в шторке.` | `POST_NOTIFICATIONS denied` | Да |
| `TILE-001` | Tile VPN Permission Required | Tile connect требует VPN permission через UI | `VpnQuickToggleExecutor.connect` | `Код ошибки: TILE-001. Нужно выдать разрешение VPN в приложении.` | tile connect permission gate reason | Да |
| `TILE-002` | Tile Toggle Failed | Ошибка переключения VPN из шторки | `VpnQuickSettingsTileService.onClick`, `VpnQuickToggleExecutor` fallback | `Код ошибки: TILE-002. Не удалось переключить VPN из шторки.` | исходная причина toggle | Да |
| `SUBS-001` | Subscription Add Failed | Ошибка добавления URL-подписки | `AppViewModel.addSubscription` | `Код ошибки: SUBS-001. Не удалось добавить подписку.` | причина валидации URL/IO | Да |
| `SUBS-002` | Subscription Refresh Failed | Ошибка загрузки/обновления подписки | `AppViewModel.refreshSubscription`, `refreshAllSubscriptions` | `Код ошибки: SUBS-002. Не удалось обновить подписку.` | subscription id + HTTP/parser причина | Да |
| `SUBS-003` | Subscription Partial Update | Загружена только часть серверов | `AppViewModel.handleSubscriptionRefreshResult` | `Код ошибки: SUBS-003. Подписка обновлена частично.` | invalid count + warning details | Да |
| `SUBS-004` | Subscription Mutation Failed | Ошибка изменения параметров подписки | rename/delete/toggle/interval actions | `Код ошибки: SUBS-004. Не удалось изменить параметры подписки.` | mutation reason + subscription id | Да |
| `UI-001` | Generic UI Action Failed | Общая ошибка действия UI | `AppViewModel.handleError` fallback | `Код ошибки: UI-001. Произошла неизвестная ошибка приложения.` | operation-specific technical reason | Да |
| `UI-002` | Generic UI State Error | Некорректное состояние UI/валидация | `AppViewModel` state validations | `Код ошибки: UI-002. Недопустимое состояние интерфейса.` | validation/context reason | Да |

## HAPP/Subscription Notes

- Ошибка расшифровки `happ://crypt5/...` при импорте профиля или внешней ссылки маппится в `IMPORT-001`.
- Ошибка расшифровки `happ://crypt5/...` при добавлении URL-подписки маппится в `SUBS-001`.
- Ошибка обновления HAPP/Marzban подписки, включая HTTP, compatibility retry и parser failure, маппится в `SUBS-002`.
- Частичная загрузка, где валидные профили сохранены, а часть entries отброшена как marker/invalid, маппится в `SUBS-003`.

## Примечания по логированию

- UI всегда получает короткий формат: `Код ошибки: <CODE>. <текст>`.
- Dev-лог использует расширенный формат:
  - `[CODE] domain=<...> status=<recoverable/fatal> user='<...>' tech='<...>'`
- Для Xray runtime дополнительно пишется `runtime tail`, чтобы видеть причину без вывода сырой трассы пользователю.
