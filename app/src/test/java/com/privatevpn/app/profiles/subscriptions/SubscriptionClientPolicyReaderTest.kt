package com.privatevpn.app.profiles.subscriptions

import com.privatevpn.app.profiles.model.SubscriptionClientPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class SubscriptionClientPolicyReaderTest {
    private val now = 1794916800_000L
    private val source = "https://example.invalid/sub/synthetic"
    private val envelope = """{"v":1,"mode":"full","revision":2,"issued_at":1794916800,"ttl_seconds":300,"display":{"protected":true,"public_label":"Public label"}}"""
    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun read(value: String = envelope, status: Int = 200, responseUrl: String = source, time: Long = now) =
        SubscriptionClientPolicyReader.read(listOf(encode(value)), source, responseUrl, status, time)
    private fun changed(edit: (JSONObject) -> Unit): String = JSONObject(envelope).also(edit).toString()

    @Test fun `exact current panel headers decode display only while body acceptance stays separate`() {
        val fixture = JSONObject(javaClass.getResourceAsStream("/subscriptions/nora-v1.json")!!.bufferedReader().use { it.readText() })
        val cases = fixture.getJSONArray("response_cases")
        val fixedNow = fixture.getLong("clock_unix_seconds") * 1000
        for (index in 0 until cases.length()) {
            val sample = cases.getJSONObject(index)
            val headers = sample.getJSONObject("response_headers")
            val header = headers.optString("X-Nora-Metadata").takeIf { it.isNotEmpty() }
            val candidate = SubscriptionClientPolicyReader.read(listOfNotNull(header), source, source, sample.getInt("http_status"), fixedNow)
            if (header != null && sample.getInt("http_status") == 200) {
                assertNotNull(sample.getString("name"), candidate)
                assertEquals(sample.getJSONObject("metadata").getJSONObject("display").getBoolean("protected"), candidate!!.endpointProtectionEnabled)
            } else assertNull(candidate)
        }
    }

    @Test fun `display only negotiation and full envelope preserve nullable label`() {
        assertEquals("display-v1", SubscriptionClientPolicyReader.requestHeaders["X-Nora-Capabilities"])
        assertEquals(true, read()?.`protected`)
        assertEquals(now + 300_000, read()?.expiresAtMs)
        assertNull(read(changed { it.getJSONObject("display").put("public_label", JSONObject.NULL) })?.publicLabel)
        assertEquals("null", read(changed { it.getJSONObject("display").put("public_label", "null") })?.publicLabel)
    }
    @Test fun `absent malformed duplicate and canceled protocol data cannot clear protection`() {
        val current = read()!!
        for (headers in listOf(emptyList(), listOf("bad+base64"), listOf(encode(envelope), encode(envelope)), listOf(encode(envelope) + ", " + encode(envelope)))) {
            val incoming = SubscriptionClientPolicyReader.read(headers, source, source, 200, now)
            assertNull(incoming)
            assertEquals(current, SubscriptionClientPolicyReader.merge(current, incoming))
        }
        for (key in listOf("status", "access", "availability", "message")) assertNull(read(changed { it.put(key, JSONObject()) }))
    }
    @Test fun `strict JSON rejects duplicate escaped keys malformed types and trailing data`() {
        assertNull(read(envelope.replace("\"protected\":true", "\"protected\":true,\"protected\":false")))
        assertNull(read(envelope.replace("\"protected\":true", "\"protected\":true,\"prot\\u0065cted\":false")))
        assertNull(read(envelope.dropLast(1) + ",}"))
        assertNull(read(envelope + " garbage"))
        for (invalid in listOf(changed { it.put("v", 2) }, envelope.replace("\"v\":1,", "\"v\":1.0,"),
            changed { it.put("mode", "patch") }, changed { it.remove("revision") },
            changed { it.getJSONObject("display").put("protected", "false") },
            changed { it.getJSONObject("display").put("protected", JSONObject.NULL) },
            changed { it.getJSONObject("display").remove("public_label") })) assertNull(read(invalid))
    }
    @Test fun `freshness safe integer and text bounds reject invalid envelopes`() {
        assertNull(read(changed { it.put("issued_at", 1794917101L) }))
        assertNull(read(time = now + 300_000))
        assertNull(read(changed { it.put("ttl_seconds", 29) }))
        assertNull(read(changed { it.put("ttl_seconds", 86401) }))
        assertNull(read(changed { it.put("revision", 9007199254740992L) }))
        for (bad in listOf("a\r\nb", "a\u202Eb", "a\u200Bb", "e\u0301", "a".repeat(81), "😀".repeat(65), "<b>Label</b>")) {
            assertNull(read(changed { it.getJSONObject("display").put("public_label", bad) }))
        }
        assertNotNull(read(changed { it.getJSONObject("display").put("public_label", "😀".repeat(60)) }))
    }
    @Test fun `HTTP200 same HTTPS origin is the only authoritative metadata response`() {
        for (code in listOf(201, 204, 304, 403, 503)) assertNull(read(status = code))
        assertNull(read(responseUrl = "https://other.invalid/sub/synthetic"))
        assertNull(read(responseUrl = "http://example.invalid/sub/synthetic"))
        assertNull(read(responseUrl = "https://example.invalid:8443/sub/synthetic"))
        assertNotNull(read(responseUrl = "https://EXAMPLE.invalid:443/sub/synthetic/happ"))
    }
    @Test fun `older revision time or same pair conflicts preserve protection and replay marks`() {
        val current = read()!!
        assertEquals(current, SubscriptionClientPolicyReader.merge(current, current.copy()))
        for (conflict in listOf(current.copy(`protected` = false), current.copy(publicLabel = "Changed"),
            current.copy(expiresAtMs = current.expiresAtMs!! + 1000),
            current.copy(revision = 100, issuedAtMs = now - 1000), current.copy(revision = 1, issuedAtMs = now + 1000))) {
            assertEquals(current, SubscriptionClientPolicyReader.merge(current, conflict))
        }
        val refreshed = current.copy(issuedAtMs = now + 1000, expiresAtMs = now + 301_000)
        assertEquals(refreshed, SubscriptionClientPolicyReader.merge(current, refreshed))
        val clear = refreshed.copy(`protected` = false, publicLabel = null, revision = 3)
        assertEquals(clear, SubscriptionClientPolicyReader.merge(current, clear))
        assertEquals(current, SubscriptionClientPolicyReader.merge(current, read(time = now + 300_000)))
    }
    @Test fun `invalid UTF8 oversized payload and duplicate future keys are rejected`() {
        assertNull(SubscriptionClientPolicyReader.read(listOf("a".repeat(4097)), source, source, 200, now))
        val invalidUtf8 = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(0xFF.toByte()))
        assertNull(SubscriptionClientPolicyReader.read(listOf(invalidUtf8), source, source, 200, now))
        assertNotNull(read(changed { it.put("future", JSONObject().put("ignored", true)) }))
        assertNull(read(envelope.dropLast(1) + ",\"future\":{\"x\":1,\"x\":2}}"))
    }
}
