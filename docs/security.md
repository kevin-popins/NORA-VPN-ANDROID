# Security and Secret Hygiene

## Что Никогда Не Коммитим
- Реальные VPN-конфиги и дампы подписок.
- Реальные `PrivateKey`, `PresharedKey`, `PublicKey`, UUID, endpoint-адреса провайдеров.
- Реальные subscription URL, токены и любые авторизационные данные.
- Реальные `happ://crypt5/...` payload: после расшифровки они могут содержать subscription URL, токены или готовые профили.
- Реальные `happ://routing/...` ссылки, если в них есть приватные домены, DNS hosts или provider-specific правила маршрутизации.
- Локальные файлы с секретами (`.env*`, приватные `.conf`, keystore-артефакты).

## Политика Тестовых Fixture
- В тестах используются только синтетические значения.
- Любые ключи и endpoint-данные в тестах должны быть явно фейковыми.
- Для IP в примерах используем только зарезервированные диапазоны документации (например `198.51.100.0/24`).
- Для URL в примерах используем нейтральные домены (`example.com`) или чистые схемы без реальных данных.

## Быстрая Проверка Перед Коммитом
Проверяйте дерево репозитория минимум этими командами:

```powershell
rg -n --hidden -S "PrivateKey\\s*=|PresharedKey\\s*=|PublicKey\\s*=|Endpoint\\s*=" . -g "!.git/**" -g "!**/build/**"
rg -n --hidden -S "vless://|vmess://|trojan://|happ://|https?://|token=|access_token" . -g "!.git/**" -g "!**/build/**"
```

Если найдено что-то похожее на реальные данные провайдера, такие строки нужно заменить на синтетические до коммита.

## Диагностика Совместимости
- В логах для HAPP/Marzban подписок используйте body signature, counts, endpoint variant и compatibility mode.
- Не вставляйте в issue/PR полные `crypt5`, routing или subscription URL.
- Параметры `pbk`, `publicKey`, `password`, `sid`, `shortId` должны быть замаскированы.
