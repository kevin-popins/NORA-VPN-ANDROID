# NoraVPN

Android VPN-клиент с поддержкой современных профилей и подписок: Xray/VLESS/REALITY, HAPP-compatible подписки и AmneziaWG 2.0.

![Android](https://img.shields.io/badge/Android-26%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![VPN](https://img.shields.io/badge/VPN-Xray%20%7C%20VLESS%20%7C%20REALITY-0A7EA4)
![AmneziaWG](https://img.shields.io/badge/AmneziaWG-2.0-1F9D55)
![Platform](https://img.shields.io/badge/Platform-Android-blue)
![Status](https://img.shields.io/badge/Status-Active%20Development-orange)
![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)

## Что Это За Проект

Ключевая идея приложения в более безопасной работе режима per-app VPN. В обычных VLESS/xray/sing-box клиентах проблема часто связана с локальным SOCKS5-прокси: стороннее приложение может попытаться получить через него внешний IP VPN-сервера даже при включённом split tunneling. Эта модель атаки подробно описана в статье на Хабре, а также в открытых PoC-репозиториях YourVPNDead и ProxyBypass. NoraVPN учитывает этот класс проблем и в режиме **раздельного туннелирования** не даёт приложениям вне VPN получить прямой IP VPN-сервера через локальный обходной канал. https://habr.com/ru/articles/1020080/
Стороннее приложение все равно сможет определить по косвенным признакам что на устройстве включен VPN. Но задачей является защитить выходной IP.
Для максимальной изоляции в приложении предусмотрен сценарий с системным **Always-on VPN** и **«Блокировать подключения без VPN»**. выбранные приложения работают через VPN, а приложения вне VPN не получают вообще никакой доступ в сеть. Это особенно полезно для сценариев, где важно минимизировать утечки и исключить прямой выход трафика мимо туннеля. 

Приложение протестировано на открытых инструментах проверки:
- **YourVPNDead** https://github.com/loop-uh/yourvpndead
- **ProxyBypass (per-app-split-bypass-poc)** https://github.com/runetfreedom/per-app-split-bypass-poc

> Важно: после установки откройте **Настройки** и включите **localhost SOCKS**, затем задайте **логин и пароль**. Защищённый localhost SOCKS является обязательной частью безопасной настройки клиента.

## Возможности
- Подключение по `Xray / VLESS / REALITY`
- Подключение по `AmneziaWG 2.0`
- Импорт профилей из ссылок и файлов
- Импорт подписок по URL
- Импорт внешних ссылок через Android `Открыть в...` / `Поделиться`
- Обновление подписок вручную и автоматически
- Выбор активного сервера/профиля
- Раздельное туннелирование (только выбранные приложения идут через VPN)
- Поддержка локального `localhost SOCKS5`
- Быстрый доступ к VPN через системную плитку Android (Quick Settings)
- Foreground-уведомление с управлением подключением
- Поддержка metadata подписок (трафик, срок действия, провайдерские поля)
- Расшифровка HAPP `crypt5` подписок и профилей
- Совместимость с HAPP/Marzban подписками, routing-профилями и провайдерскими конфигами

## Скриншоты
![Главный экран](docs/screenshots/main.jpg)
![Экран профилей](docs/screenshots/profiles.jpg)
![Раздельное туннелирование](docs/screenshots/tunneling.jpg)

## Важно: Localhost SOCKS
> Для безопасной работы обязательно включите `localhost SOCKS` в настройках и задайте **логин и пароль**.  
> SOCKS-доступ должен быть защищён авторизацией (логин/пароль), не оставляйте его без защиты.

## Поддерживаемые Форматы Импорта
- `vless://`
- `vmess://`
- `trojan://`
- `happ://crypt5/...`
- `happ://routing/add/...` и `happ://routing/onadd/...`
- Raw `Xray JSON`
- `AmneziaWG 2.0` (`.conf`)
- Подписки по `URL`

Для VLESS-ссылок поддерживается расширенная нормализация параметров провайдеров:
- REALITY aliases: `pbk/publicKey/publickey/password`, `sid/shortId/shortid/short_id`, `sni/serverName/servername/server_name`, `fp/fingerprint`, `spx/spiderX/spiderx/spider_x`;
- TLS/transport параметры: `alpn`, `allowInsecure`, `host/authority`, `path`, `serviceName`, `mode`, `headerType`;
- транспорты: `tcp/raw`, `ws/websocket`, `grpc`, `h2/http`, `httpupgrade`, `splithttp`, `xhttp`.

## Как Начать
1. Установите приложение.
2. Добавьте профиль или подписку.
3. Выберите сервер (активный профиль).
4. Откройте настройки и включите `localhost SOCKS`.
5. Задайте логин и пароль для SOCKS.
6. Нажмите подключение к VPN.
7. При необходимости включите раздельное туннелирование и выберите приложения.

## Раздельное Туннелирование
Режим позволяет направлять через VPN только выбранные приложения.  
Если приложение не входит в список, его трафик пойдёт в обычном режиме без VPN.

## Подписки
NoraVPN умеет загружать списки серверов из подписок провайдеров по URL.  
Подписки можно обновлять, а дополнительные metadata/служебные поля провайдера используются для совместимости и отображения статуса.

Если провайдер выдает HAPP `crypt5`, приложение расшифровывает ссылку перед добавлением. Если подписка содержит HAPP Routing в теле или HTTP-заголовке `routing`, правила `direct`, `proxy`, `block` и DNS из routing-профиля применяются к импортируемым Xray-профилям.

## Уведомления И Плитка В Шторке
- Foreground notification показывает текущий статус VPN и даёт быстрые действия.
- Quick Settings tile позволяет включать/выключать VPN прямо из системной шторки Android.

## Стек И Технологии
- Android (`minSdk 26`)
- Kotlin
- Jetpack Compose (Material 3)
- `VpnService`
- Room
- WorkManager
- DataStore

## Документация
- [Архитектура](docs/architecture.md)
- [Подписки и совместимость](docs/subscriptions.md)
- [Каталог кодов ошибок](docs/error-codes.md)
- [Безопасность и секреты](docs/security.md)

## Статус Проекта
Рабочая версия, активная разработка продолжается.  
Функциональность пригодна для ежедневного использования, при этом отдельные части продолжают дорабатываться.

## Лицензия
Проект распространяется по лицензии **MIT**.  
Полный текст: [LICENSE](LICENSE).
