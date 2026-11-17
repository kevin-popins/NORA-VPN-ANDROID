package com.privatevpn.app.profiles.subscriptions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class SubscriptionServiceRecordsTest {
    @Test fun `marker only HTTP200 without HWID headers triggers existing compatibility modes`() {
        val marker = "vless://00000000-0000-0000-0000-000000000000@127.0.0.1:1#Install%20official%20app"
        assertTrue(SubscriptionServiceRecords.isServiceRecord(marker))
        assertTrue(SubscriptionCompatibilityPolicy.shouldProbe(0, 1, false))
    }

    @Test fun `real profiles always prevent marker only fallback`() {
        assertFalse(SubscriptionCompatibilityPolicy.shouldProbe(2, 20, true))
    }

    @Test fun `real JSON named Android Happ Windows remains connectable`() {
        val json = """{"remarks":"Android Happ Windows","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"198.51.100.7","port":443,"users":[{"id":"11111111-1111-1111-1111-111111111111"}]}]}}]}"""
        assertFalse(SubscriptionServiceRecords.isServiceRecord(json))
    }

    @Test fun `JSON local placeholder is a marker independent of arbitrary notice text`() {
        val json = """{"remarks":"Работы до 18:00","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"0.0.0.0","port":1}]}}]}"""
        assertTrue(SubscriptionServiceRecords.isServiceRecord(json))
    }

    @Test fun `mixed JSON endpoints are not discarded as a single marker`() {
        val json = """{"outbounds":[{"settings":{"vnext":[{"address":"127.0.0.1","port":1},{"address":"198.51.100.7","port":443}]}}]}"""
        assertFalse(SubscriptionServiceRecords.isServiceRecord(json))
    }

    @Test fun `base64 VMess synthetic endpoint is recognized without parsing as URI host`() {
        val json = """{"add":"127.0.0.1","port":"1","id":"00000000-0000-0000-0000-000000000000","ps":"Notice"}"""
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        assertTrue(SubscriptionServiceRecords.isServiceRecord("vmess://$encoded"))
    }

    @Test fun `real VMess server named install app is retained`() {
        val json = """{"add":"198.51.100.7","port":"443","id":"11111111-1111-1111-1111-111111111111","ps":"Install app"}"""
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        assertFalse(SubscriptionServiceRecords.isServiceRecord("vmess://$encoded"))
    }

    private val syntheticCredential = "00000000-0000-0000-0000-000000000000"
    private val syntheticHost = "localhost"
    private val syntheticHash = SubscriptionServiceRecords.reservedTupleSha256(syntheticHost, syntheticCredential, 1)
    private fun syntheticUri() = "vless://$syntheticCredential@$syntheticHost:1?encryption=none&type=tcp&security=none#notice"
    private fun known(raw: String) = SubscriptionServiceRecords.isKnownTechnicalEntry(raw, null, syntheticHash)
    private fun syntheticJson(label: String = "Пауза — вернёмся утром"): org.json.JSONObject {
        val user = org.json.JSONObject().put("id", syntheticCredential).put("encryption", "none")
        val server = org.json.JSONObject().put("address", syntheticHost).put("port", 1).put("users", org.json.JSONArray().put(user))
        val proxy = org.json.JSONObject().put("tag", "synthetic-marker").put("protocol", "vless")
            .put("settings", org.json.JSONObject().put("vnext", org.json.JSONArray().put(server)))
            .put("streamSettings", org.json.JSONObject().put("network", "tcp").put("security", "none"))
        return org.json.JSONObject().put("remarks", label)
            .put("meta", org.json.JSONObject().put("serverDescription", "Аварийная заглушка"))
            .put("outbounds", org.json.JSONArray().put(proxy))
    }

    @Test fun `known technical classification ignores all display text semantics`() {
        for (label in listOf("Пауза — вернёмся утром", "Приложение можно оставить открытым", "Install official app", "<exact configured text>", "")) {
            assertTrue(known(syntheticUri().substringBefore('#') + "#" + label))
            assertTrue(known(syntheticJson(label).toString()))
        }
        assertFalse(known("generic announcement"))
    }

    @Test fun `unknown install expired and generic markers are filtered rather than technical`() {
        val otherMarker = syntheticUri().replace(syntheticCredential, "11111111-1111-4111-8111-111111111111")
        assertTrue(SubscriptionServiceRecords.isServiceRecord(otherMarker))
        assertFalse(known(otherMarker))
        assertFalse(known(syntheticUri().substringBefore('?') + "#Unknown marker"))
    }

    @Test fun `exact known technical role rejects other tuple transport and duplicate query keys`() {
        val raw = syntheticUri()
        val invalid = listOf(raw.replace(syntheticHost, "127.0.0.2"),
            raw.replace(syntheticCredential, "11111111-1111-4111-8111-111111111111"),
            raw.replace("security=none", "security=tls"), raw.replace("type=tcp", "type=ws"),
            raw.replace("#notice", "&security=none#notice"), raw.replace("#notice", "&sni=example.invalid#notice"))
        invalid.forEach { assertFalse(known(it)) }
    }

    @Test fun `JSON technical role requires fixed role inert tuple and no real proxy`() {
        val noRole = syntheticJson().also { it.remove("meta") }
        val otherRole = syntheticJson().also { it.getJSONObject("meta").put("serverDescription", "Normal location") }
        val extraTls = syntheticJson().also { it.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").put("tlsSettings", org.json.JSONObject()) }
        val wrongCredential = syntheticJson().also { it.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users").getJSONObject(0).put("id", "11111111-1111-4111-8111-111111111111") }
        val wrongHost = syntheticJson().also { it.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).put("address", "127.0.0.2") }
        val realProxy = org.json.JSONObject(syntheticJson().getJSONArray("outbounds").getJSONObject(0).toString()).also {
            it.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).put("address", "192.0.2.10").put("port", 443)
        }
        val mixed = syntheticJson().also { it.getJSONArray("outbounds").put(realProxy) }
        val malformedOutbound = syntheticJson().also { it.getJSONArray("outbounds").put("not an outbound object") }
        val fractionalPort = syntheticJson().also { it.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).put("port", 1.5) }
        listOf(noRole, otherRole, extraTls, wrongCredential, wrongHost, mixed, malformedOutbound, fractionalPort).forEach {
            assertFalse(known(it.toString()))
        }
    }

    @Test fun `generic cached subscription markers are blocked without changing manual direct profiles`() {
        val raw = syntheticUri().replace(syntheticCredential, "11111111-1111-4111-8111-111111111111")
        val cached = com.privatevpn.app.profiles.model.VpnProfile("cached", "Unknown", com.privatevpn.app.profiles.model.ProfileType.VLESS,
            raw, null, emptyList(), false, false, emptyList(), 0, parentSubscriptionId = "sub")
        assertFalse(cached.isTechnicalEntry)
        assertFalse(cached.isConnectable)
        assertTrue(cached.copy(parentSubscriptionId = null).isConnectable)
        assertFalse(cached.copy(parentSubscriptionId = null, isTechnicalEntry = true).isConnectable)
    }

    @Test fun `ordinary routing blackhole is not a notice endpoint`() {
        assertFalse(SubscriptionServiceRecords.isServiceRecord("""{"outbounds":[{"protocol":"blackhole","settings":{}}]}"""))
    }
}
