package com.privatevpn.app.core.error

data class AppError(
    val code: AppErrorCode,
    val userMessage: String = code.defaultUserMessage,
    val technicalReason: String? = null,
    val recoverable: Boolean = code.recoverable
) {
    fun toUiMessage(): String = "Код ошибки: ${code.code}. $userMessage"

    fun toLogMessage(): String {
        val recoverableLabel = if (recoverable) "recoverable" else "fatal"
        val reason = technicalReason?.takeIf { it.isNotBlank() } ?: "-"
        return "[${code.code}] domain=${code.domain} status=$recoverableLabel user='$userMessage' tech='$reason'"
    }
}

class AppException(
    val appError: AppError,
    cause: Throwable? = null
) : RuntimeException(appError.technicalReason ?: appError.userMessage, cause)

object AppErrors {
    fun fromThrowable(
        error: Throwable,
        fallbackCode: AppErrorCode,
        fallbackUserMessage: String = fallbackCode.defaultUserMessage
    ): AppError {
        if (error is AppException) return error.appError

        return AppError(
            code = fallbackCode,
            userMessage = fallbackUserMessage,
            technicalReason = sanitizeTechnicalReason(error.message),
            recoverable = fallbackCode.recoverable
        )
    }

    fun backendSwitchInProgress(
        fromBackend: String?,
        toBackend: String?,
        technicalReason: String? = null
    ): AppError {
        val user = "Идёт переключение движка подключения. Нажмите ещё раз через секунду."
        val tech = buildString {
            append("switch in progress")
            fromBackend?.let { append(", from=").append(it) }
            toBackend?.let { append(", to=").append(it) }
            sanitizeTechnicalReason(technicalReason)?.let { append(", reason=").append(it) }
        }
        return AppError(
            code = AppErrorCode.BACKEND_001,
            userMessage = user,
            technicalReason = tech,
            recoverable = true
        )
    }

    fun backendSwitchTimeout(
        fromBackend: String?,
        toBackend: String?,
        technicalReason: String? = null
    ): AppError {
        val tech = buildString {
            append("switch timeout")
            fromBackend?.let { append(", from=").append(it) }
            toBackend?.let { append(", to=").append(it) }
            sanitizeTechnicalReason(technicalReason)?.let { append(", reason=").append(it) }
        }
        return AppError(
            code = AppErrorCode.BACKEND_002,
            technicalReason = tech,
            recoverable = true
        )
    }

    fun backendSwitchFailed(
        fromBackend: String?,
        toBackend: String?,
        technicalReason: String? = null
    ): AppError {
        val tech = buildString {
            append("switch failed")
            fromBackend?.let { append(", from=").append(it) }
            toBackend?.let { append(", to=").append(it) }
            sanitizeTechnicalReason(technicalReason)?.let { append(", reason=").append(it) }
        }
        return AppError(
            code = AppErrorCode.BACKEND_003,
            technicalReason = tech,
            recoverable = true
        )
    }

    fun xrayRuntimeStartFailed(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.XRAY_101,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun xrayRuntimeStopFailed(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.XRAY_102,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun awgRuntimeStartFailed(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.AWG_101,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun awgRuntimeStopFailed(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.AWG_102,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun krotRuntimeStartFailed(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.KROT_101,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun krotRuntimeStopFailed(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.KROT_102,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun socksInvalidPort(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.SOCKS_001,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun socksAuthRequired(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.SOCKS_002,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun splitTunnelingNoTrustedApps(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.SPLIT_001,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun splitTunnelingApplyFailed(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.SPLIT_002,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun notificationPermissionRequired(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.NOTIFY_001,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun vpnPermissionRequired(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.VPN_001,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun tilePermissionRequired(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.TILE_001,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun tileToggleFailed(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.TILE_002,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun profileImportFailed(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.IMPORT_001,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun profileUnsupported(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.IMPORT_002,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun subscriptionAddFailed(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.SUBS_001,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun subscriptionRefreshFailed(technicalReason: String? = null): AppError =
        AppError(
            code = AppErrorCode.SUBS_002,
            technicalReason = sanitizeTechnicalReason(technicalReason),
            recoverable = true
        )

    fun subscriptionPartialUpdate(
        userMessage: String = AppErrorCode.SUBS_003.defaultUserMessage,
        technicalReason: String? = null
    ): AppError = AppError(
        code = AppErrorCode.SUBS_003,
        userMessage = userMessage,
        technicalReason = sanitizeTechnicalReason(technicalReason),
        recoverable = true
    )

    fun subscriptionMutationFailed(
        userMessage: String = AppErrorCode.SUBS_004.defaultUserMessage,
        technicalReason: String? = null
    ): AppError = AppError(
        code = AppErrorCode.SUBS_004,
        userMessage = userMessage,
        technicalReason = sanitizeTechnicalReason(technicalReason),
        recoverable = true
    )

    fun genericUiActionFailed(
        userMessage: String,
        technicalReason: String? = null
    ): AppError = AppError(
        code = AppErrorCode.UI_001,
        userMessage = userMessage,
        technicalReason = sanitizeTechnicalReason(technicalReason),
        recoverable = true
    )

    fun genericUiStateError(
        userMessage: String,
        technicalReason: String? = null
    ): AppError = AppError(
        code = AppErrorCode.UI_002,
        userMessage = userMessage,
        technicalReason = sanitizeTechnicalReason(technicalReason),
        recoverable = true
    )

    private fun sanitizeTechnicalReason(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) return null
        return normalized.take(MAX_TECHNICAL_REASON_CHARS)
    }

    private const val MAX_TECHNICAL_REASON_CHARS: Int = 4000
}
