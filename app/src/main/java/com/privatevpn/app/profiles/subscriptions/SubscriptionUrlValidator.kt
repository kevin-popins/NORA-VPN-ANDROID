package com.privatevpn.app.profiles.subscriptions

import java.net.URI
import java.nio.charset.StandardCharsets

/** The same URL validation applies before storage and to nested deep-link URLs. */
object SubscriptionUrlValidator {
    fun validate(value: String): String {
        require(value.toByteArray(StandardCharsets.UTF_8).size <= 8 * 1024) { "Subscription URL is too large" }
        require(value.none { it.isWhitespace() || it.isISOControl() || it == '\uFEFF' || it == '\u200B' || it == '\u2060' }) {
            "Subscription URL contains whitespace or invisible characters"
        }
        val uri = runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("Malformed subscription URL") }
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) { "Subscription URL must use HTTP or HTTPS" }
        require(!uri.host.isNullOrBlank()) { "Subscription URL must contain a host" }
        require(uri.port == -1 || uri.port in 1..65535) { "Subscription URL port is invalid" }
        require(uri.rawUserInfo == null) { "Subscription URL user info is not allowed" }
        require(uri.rawFragment == null) { "Subscription URL fragment is not allowed" }
        return value
    }
}
