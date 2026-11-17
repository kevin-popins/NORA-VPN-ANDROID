package com.privatevpn.app.profiles.subscriptions

import com.privatevpn.app.profiles.model.SubscriptionClientPolicy
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Base64

/** Strict v1 metadata, scoped by the caller to one stored subscription and validated body. */
object SubscriptionClientPolicyReader {
    const val HEADER = "X-Nora-Metadata"
    val requestHeaders = mapOf(
        "X-Nora-Client" to "nora-android",
        "X-Nora-Metadata-Accept" to "1",
        "X-Nora-Capabilities" to "display-v1"
    )

    fun read(headerValues: List<String>, sourceUrl: String, responseUrl: String, statusCode: Int, nowMs: Long): SubscriptionClientPolicy? = runCatching {
        require(statusCode == 200)
        require(sameHttpsOrigin(sourceUrl, responseUrl))
        require(headerValues.size == 1)
        val encoded = headerValues.single()
        require(encoded.length in 1..4096 && encoded.all { it.code < 128 })
        require(encoded.matches(Regex("^[A-Za-z0-9_-]+$")) && encoded.length % 4 != 1)
        val bytes = Base64.getUrlDecoder().decode(encoded)
        require(bytes.size <= 3072)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded)
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val root = StrictJson(text).parse() as? Map<*, *> ?: error("Object required")
        require(root.integer("v") == 1L && root["mode"] == "full")
        val revision = root.integer("revision").also { require(it in 0..MAX_SAFE_INTEGER) }
        val issuedAt = root.integer("issued_at").also { require(it >= 0 && it <= nowMs / 1000 + 300) }
        val ttl = root.integer("ttl_seconds").also { require(it in 30..86400) }
        require(issuedAt + ttl > nowMs / 1000)
        val display = root["display"] as? Map<*, *> ?: error("Display object required")
        require(root.keys.none { it in setOf("status", "access", "availability", "message") })
        val protection = display["protected"] as? Boolean ?: error("Boolean required")
        val label = display.nullableText("public_label")?.also { validatePublicText(it, 80, 256) }
        SubscriptionClientPolicy(
            `protected` = protection, publicLabel = label,
            issuedAtMs = Math.multiplyExact(issuedAt, 1000), revision = revision,
            expiresAtMs = Math.multiplyExact(issuedAt + ttl, 1000)
        )
    }.getOrNull()

    fun canAdopt(current: SubscriptionClientPolicy?, incoming: SubscriptionClientPolicy?): Boolean {
        incoming ?: return false
        current ?: return true
        if (current.revision == null) return true // Legacy receipt time is not a v1 replay watermark.
        val oldRevision = current.revision ?: -1
        val newRevision = incoming.revision ?: return false
        val oldIssued = current.issuedAtMs ?: -1
        val newIssued = incoming.issuedAtMs ?: return false
        if (newIssued < oldIssued || newRevision < oldRevision) return false
        if (newRevision > oldRevision) return true
        if (current.endpointProtectionEnabled != incoming.endpointProtectionEnabled || current.publicLabel != incoming.publicLabel) return false
        return newIssued > oldIssued || current.expiresAtMs == incoming.expiresAtMs
    }

    fun merge(current: SubscriptionClientPolicy?, incoming: SubscriptionClientPolicy?): SubscriptionClientPolicy? =
        if (canAdopt(current, incoming)) incoming else current

    fun sameHttpsOrigin(sourceUrl: String, responseUrl: String): Boolean = runCatching {
        val source = URI(sourceUrl)
        val response = URI(responseUrl)
        source.scheme.equals("https", true) && response.scheme.equals("https", true) &&
            source.rawUserInfo == null && response.rawUserInfo == null && !source.host.isNullOrBlank() &&
            source.host.equals(response.host, true) && effectivePort(source) == effectivePort(response)
    }.getOrDefault(false)

    private fun effectivePort(uri: URI): Int = if (uri.port == -1) 443 else uri.port
    private fun Map<*, *>.integer(key: String): Long {
        val token = this[key] as? JsonNumber ?: error("Integer required")
        require(token.text.matches(Regex("0|[1-9][0-9]*")))
        return token.text.toLong()
    }
    private fun Map<*, *>.nullableText(key: String): String? {
        require(containsKey(key))
        val value = this[key] ?: return null
        return value as? String ?: error("Text or null required")
    }
    private fun validatePublicText(value: String, scalarLimit: Int, byteLimit: Int) {
        require(value.isNotBlank())
        require(value.toByteArray(StandardCharsets.UTF_8).size <= byteLimit)
        require(value.codePointCount(0, value.length) <= scalarLimit)
        require(Normalizer.isNormalized(value, Normalizer.Form.NFC))
        value.codePoints().forEach { point ->
            require(!Character.isISOControl(point) && Character.getType(point) != Character.FORMAT.toInt())
            require(point !in 0xD800..0xDFFF && point != 0x2028 && point != 0x2029)
        }
        require(!Regex("</?[A-Za-z][^>]*>").containsMatchIn(value))
    }
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private data class JsonNumber(val text: String)

    /** Android JSONObject permits duplicate keys, so validate without its permissive parser. */
    private class StrictJson(private val text: String) {
        private var index = 0
        fun parse(): Any? {
            val result = value(0)
            whitespace()
            require(index == text.length)
            return result
        }
        private fun value(depth: Int): Any? {
            require(depth <= 16)
            whitespace()
            require(index < text.length)
            return when (text[index]) {
                '{' -> {
                    index++; whitespace()
                    val fields = linkedMapOf<String, Any?>()
                    if (take('}')) fields else {
                        do {
                            whitespace()
                            require(index < text.length && text[index] == '"')
                            val key = string()
                            require(!fields.containsKey(key))
                            whitespace(); require(take(':'))
                            fields[key] = value(depth + 1)
                            whitespace()
                        } while (take(','))
                        require(take('}'))
                        fields
                    }
                }
                '[' -> {
                    index++; whitespace()
                    val items = mutableListOf<Any?>()
                    if (take(']')) items else {
                        do { items += value(depth + 1); whitespace() } while (take(','))
                        require(take(']'))
                        items
                    }
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> {
                    val match = NUMBER.find(text, index)
                    require(match != null && match.range.first == index)
                    index += match.value.length
                    JsonNumber(match.value)
                }
            }
        }
        private fun string(): String {
            require(take('"'))
            val result = StringBuilder()
            var ended = false
            while (index < text.length) {
                val next = text[index++]
                if (next == '"') { ended = true; break }
                require(next >= ' ')
                if (next != '\\') { result.append(next); continue }
                require(index < text.length)
                result.append(when (val escaped = text[index++]) {
                    '"', '\\', '/' -> escaped
                    'b' -> '\b'
                    'f' -> '\u000C'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        require(index + 4 <= text.length)
                        val value = text.substring(index, index + 4)
                        require(value.all { it.digitToIntOrNull(16) != null })
                        index += 4
                        value.toInt(16).toChar()
                    }
                    else -> error("Invalid escape")
                })
            }
            require(ended)
            val value = result.toString()
            var cursor = 0
            while (cursor < value.length) {
                val character = value[cursor++]
                if (character.isHighSurrogate()) require(cursor < value.length && value[cursor++].isLowSurrogate())
                else require(!character.isLowSurrogate())
            }
            return value
        }
        private fun literal(word: String, value: Any?): Any? {
            require(text.startsWith(word, index))
            index += word.length
            return value
        }
        private fun take(character: Char): Boolean {
            if (index >= text.length || text[index] != character) return false
            index++
            return true
        }
        private fun whitespace() { while (index < text.length && text[index] in " \r\n\t") index++ }
        companion object { private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?") }
    }
}
