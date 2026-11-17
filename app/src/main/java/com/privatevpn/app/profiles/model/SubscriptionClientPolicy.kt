package com.privatevpn.app.profiles.model

/** Subscription-scoped endpoint display restrictions; never a service notification model. */
data class SubscriptionClientPolicy(
    val `protected`: Boolean? = null,
    val publicLabel: String? = null,
    val issuedAtMs: Long? = null,
    val revision: Long? = null,
    val expiresAtMs: Long? = null
) {
    val endpointProtectionEnabled: Boolean get() = `protected` == true
    val publicDisplayLabel: String get() = publicLabel?.trim()?.takeIf {
        it.isNotEmpty() && !containsEndpointText(it)
    }?.take(120) ?: "Защищённый сервер"
    companion object {
        fun containsEndpointText(value: String): Boolean = value.contains("://") ||
            Regex("""(?:[0-9]{1,3}\.){3}[0-9]{1,3}|(?:[a-zA-Z0-9-]+\.)+[a-zA-Z]{2,63}|[0-9a-fA-F]*:[0-9a-fA-F:]*:[0-9a-fA-F:]+""").containsMatchIn(value)
    }
}
