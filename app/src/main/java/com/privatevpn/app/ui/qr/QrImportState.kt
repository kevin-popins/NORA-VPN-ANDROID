package com.privatevpn.app.ui.qr

/** Only presentation-safe outcomes are observable; a QR payload never belongs in UI state. */
sealed interface QrImportState {
    data object Idle : QrImportState
    data object Loading : QrImportState
    data object Success : QrImportState
    data class Failure(val safeMessage: String) : QrImportState
}

enum class QrImportFailureCode(val safeMessage: String) {
    UNSUPPORTED("QR-код не содержит поддерживаемый VPN-профиль или подписку"),
    LOAD_FAILED("Не удалось загрузить подписку. Проверьте соединение и попробуйте ещё раз."),
    EMPTY_SERVICE("В подписке нет доступных VPN-профилей.")
}

/** A fixed category is safe to show; parser/network exception text is never forwarded. */
class QrImportException(val code: QrImportFailureCode) : Exception()
