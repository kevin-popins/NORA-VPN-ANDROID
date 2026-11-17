package com.privatevpn.app.profiles.importer

/** Removes presentation around an entire URI, never characters inside its payload. */
object ImportInputNormalizer {
    fun normalize(rawInput: String): String {
        var value = rawInput.trim().removePrefix("\uFEFF").trim()
        repeat(2) {
            val unwrapped = when {
                value.length >= 2 && value.first() == '<' && value.last() == '>' -> value.substring(1, value.lastIndex)
                value.length >= 2 && value.first() == '"' && value.last() == '"' -> value.substring(1, value.lastIndex)
                value.length >= 2 && value.first() == '\'' && value.last() == '\'' -> value.substring(1, value.lastIndex)
                value.startsWith("[") && value.endsWith(")") && value.contains("](") -> value.substringAfter("](").dropLast(1)
                else -> return value
            }.trim()
            if (!URI_PREFIX.containsMatchIn(unwrapped)) return value
            value = unwrapped
        }
        return value
    }

    private val URI_PREFIX = Regex("^(?:https?|noravpn|vless|vmess|trojan|happ)://", RegexOption.IGNORE_CASE)
}
