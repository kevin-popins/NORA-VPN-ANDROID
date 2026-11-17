package com.privatevpn.app.profiles.subscriptions

import com.privatevpn.app.profiles.model.VpnProfile
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Reuses local identity without changing the incoming transport or provider order. */
object SubscriptionProfileIdentity {
    fun reuse(existing: List<VpnProfile>, incoming: List<VpnProfile>): List<VpnProfile> {
        val assignments = arrayOfNulls<String>(incoming.size)
        val consumed = BooleanArray(existing.size)
        fun reserve(index: Int, predicate: (VpnProfile) -> Boolean) {
            val match = existing.indices.firstOrNull { oldIndex ->
                !consumed[oldIndex] && existing[oldIndex].parentSubscriptionId == incoming[index].parentSubscriptionId && predicate(existing[oldIndex])
            } ?: return
            consumed[match] = true
            assignments[index] = existing[match].id
        }
        // Reserve all exact matches before a new alias can consume their old identity.
        incoming.indices.forEach { index -> reserve(index) { it.sourceRaw == incoming[index].sourceRaw } }
        val fingerprints = existing.map(::fingerprint)
        incoming.indices.filter { assignments[it] == null }.forEach { index ->
            val fingerprint = fingerprint(incoming[index])
            val match = existing.indices.firstOrNull { oldIndex ->
                !consumed[oldIndex] && existing[oldIndex].parentSubscriptionId == incoming[index].parentSubscriptionId &&
                    fingerprints[oldIndex] == fingerprint
            }
            if (match != null) {
                consumed[match] = true
                assignments[index] = existing[match].id
            }
        }
        val used = assignments.filterNotNull().toMutableSet()
        return incoming.mapIndexed { index, profile ->
            val id = assignments[index] ?: run {
                var candidate = profile.id
                var suffix = 0
                while (!used.add(candidate)) candidate = "${profile.id}_${++suffix}"
                candidate
            }
            profile.copy(id = id)
        }
    }

    private fun fingerprint(profile: VpnProfile): String {
        val raw = profile.sourceRaw.trim()
        val normalized = profile.normalizedJson.orEmpty()
        val payload = when {
            raw.startsWith("vmess://", true) -> {
                val decoded = runCatching {
                    val base64 = raw.substringAfter("://").substringBefore('#').replace('-', '+').replace('_', '/')
                    String(Base64.getDecoder().decode(base64.padEnd((base64.length + 3) / 4 * 4, '=')), StandardCharsets.UTF_8)
                }.getOrNull()
                jsonCopy(decoded, setOf("ps")) + "\n" + jsonCopy(normalized, setOf("ps"))
            }
            raw.startsWith("{") -> jsonCopy(raw, setOf("remarks", "meta")) + "\n" + jsonCopy(normalized, setOf("remarks", "meta"))
            raw.startsWith("vless://", true) || raw.startsWith("trojan://", true) ->
                raw.substringBefore('#') + "\n" + jsonCopy(normalized, emptySet())
            else -> raw + "\n" + jsonCopy(normalized, emptySet())
        }
        val dns = canonical(JSONArray(profile.dnsServers)) + "\n" + profile.dnsFallbackApplied
        return MessageDigest.getInstance("SHA-256").digest((profile.type.name + "\n" + payload + "\n" + dns).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun jsonCopy(value: String?, excludedRootFields: Set<String>): String = runCatching {
        val copy = JSONObject(value ?: return@runCatching "")
        excludedRootFields.forEach(copy::remove)
        canonical(copy)
    }.getOrElse { value.orEmpty() }

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") {
            JSONObject.quote(it) + ":" + canonical(value.opt(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.opt(it)) }
        is Number -> JSONObject.numberToString(value)
        is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
