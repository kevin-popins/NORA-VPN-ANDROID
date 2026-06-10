package com.privatevpn.app.core.error

enum class AppErrorCode(
    val code: String,
    val domain: String,
    val defaultUserMessage: String,
    val recoverable: Boolean
) {
    VPN_001(
        code = "VPN-001",
        domain = "vpn state",
        defaultUserMessage = "Для подключения требуется разрешение VPN.",
        recoverable = true
    ),
    IMPORT_001(
        code = "IMPORT-001",
        domain = "profile import",
        defaultUserMessage = "Не удалось импортировать профиль.",
        recoverable = true
    ),
    IMPORT_002(
        code = "IMPORT-002",
        domain = "profile import",
        defaultUserMessage = "Формат профиля не поддерживается.",
        recoverable = true
    ),
    BACKEND_001(
        code = "BACKEND-001",
        domain = "backend switch",
        defaultUserMessage = "Идёт подготовка подключения. Подождите несколько секунд.",
        recoverable = true
    ),
    BACKEND_002(
        code = "BACKEND-002",
        domain = "backend switch",
        defaultUserMessage = "Переключение движка заняло слишком много времени. Повторите попытку.",
        recoverable = true
    ),
    BACKEND_003(
        code = "BACKEND-003",
        domain = "backend switch",
        defaultUserMessage = "Не удалось подготовить движок подключения.",
        recoverable = true
    ),
    XRAY_101(
        code = "XRAY-101",
        domain = "xray runtime",
        defaultUserMessage = "Не удалось запустить Xray runtime.",
        recoverable = true
    ),
    XRAY_102(
        code = "XRAY-102",
        domain = "xray runtime",
        defaultUserMessage = "Не удалось корректно остановить Xray runtime.",
        recoverable = true
    ),
    AWG_101(
        code = "AWG-101",
        domain = "awg runtime",
        defaultUserMessage = "Не удалось запустить AmneziaWG runtime.",
        recoverable = true
    ),
    AWG_102(
        code = "AWG-102",
        domain = "awg runtime",
        defaultUserMessage = "Не удалось корректно остановить AmneziaWG runtime.",
        recoverable = true
    ),
    KROT_101(
        code = "KROT-101",
        domain = "krot runtime",
        defaultUserMessage = "Не удалось запустить KRot runtime.",
        recoverable = true
    ),
    KROT_102(
        code = "KROT-102",
        domain = "krot runtime",
        defaultUserMessage = "Не удалось корректно остановить KRot runtime.",
        recoverable = true
    ),
    SOCKS_001(
        code = "SOCKS-001",
        domain = "socks/localhost",
        defaultUserMessage = "Порт SOCKS должен быть в диапазоне 1-65535.",
        recoverable = true
    ),
    SOCKS_002(
        code = "SOCKS-002",
        domain = "socks/localhost",
        defaultUserMessage = "Для localhost SOCKS обязательны логин и пароль.",
        recoverable = true
    ),
    SPLIT_001(
        code = "SPLIT-001",
        domain = "split tunneling",
        defaultUserMessage = "В режиме приватной сессии выберите хотя бы одно доверенное приложение.",
        recoverable = true
    ),
    SPLIT_002(
        code = "SPLIT-002",
        domain = "split tunneling",
        defaultUserMessage = "Не удалось применить настройки раздельного туннелирования.",
        recoverable = true
    ),
    NOTIFY_001(
        code = "NOTIFY-001",
        domain = "notification/tile",
        defaultUserMessage = "Разрешите уведомления, чтобы видеть статус VPN в шторке.",
        recoverable = true
    ),
    TILE_001(
        code = "TILE-001",
        domain = "notification/tile",
        defaultUserMessage = "Нужно выдать разрешение VPN в приложении.",
        recoverable = true
    ),
    TILE_002(
        code = "TILE-002",
        domain = "notification/tile",
        defaultUserMessage = "Не удалось переключить VPN из шторки.",
        recoverable = true
    ),
    SUBS_001(
        code = "SUBS-001",
        domain = "subscription",
        defaultUserMessage = "Не удалось добавить подписку.",
        recoverable = true
    ),
    SUBS_002(
        code = "SUBS-002",
        domain = "subscription",
        defaultUserMessage = "Не удалось обновить подписку.",
        recoverable = true
    ),
    SUBS_003(
        code = "SUBS-003",
        domain = "subscription",
        defaultUserMessage = "Подписка обновлена частично.",
        recoverable = true
    ),
    SUBS_004(
        code = "SUBS-004",
        domain = "subscription",
        defaultUserMessage = "Не удалось изменить параметры подписки.",
        recoverable = true
    ),
    UI_001(
        code = "UI-001",
        domain = "generic ui/state",
        defaultUserMessage = "Произошла неизвестная ошибка приложения.",
        recoverable = true
    ),
    UI_002(
        code = "UI-002",
        domain = "generic ui/state",
        defaultUserMessage = "Недопустимое состояние интерфейса.",
        recoverable = true
    )
}
