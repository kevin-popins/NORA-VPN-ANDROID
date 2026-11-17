package com.privatevpn.app.profiles.subscriptions

import com.privatevpn.app.profiles.model.ProfileType
import com.privatevpn.app.profiles.model.VpnProfile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class SubscriptionProfileIdentityTest {
    private val config = """{"remarks":"Old","meta":{"serverDescription":"City / 1"},"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"192.0.2.1","port":443,"users":[{"id":"11111111-1111-4111-8111-111111111111"}]}]},"streamSettings":{"network":"tcp","security":"reality","realitySettings":{"publicKey":"synthetic-key","shortId":"abcd"}}}],"routing":{"rules":[{"outboundTag":"proxy","domain":["example.invalid"]}]},"dns":{"servers":["1.1.1.1","8.8.8.8"]}}"""
    private fun profile(id: String, raw: String = config, normalized: String? = raw, order: Int = 0, parent: String = "sub-a", type: ProfileType = ProfileType.XRAY_VLESS_REALITY) =
        VpnProfile(id, "Name", type, raw, normalized, listOf("1.1.1.1"), false, false, emptyList(), 1, parent, order)
    private fun displayConfig(name: String, description: String = "New description") = JSONObject(config)
        .put("remarks", name).put("meta", JSONObject().put("serverDescription", description)).toString()

    @Test fun `display only changes keep id and exact incoming raw normalized data`() {
        val incoming = profile("generated", displayConfig("New", "City / 999"), order = 7)
        val result = SubscriptionProfileIdentity.reuse(listOf(profile("old")), listOf(incoming)).single()
        assertEquals("old", result.id)
        assertEquals(incoming.copy(id = "old"), result)
        assertEquals(incoming.sourceRaw, result.sourceRaw)
        assertEquals(incoming.normalizedJson, result.normalizedJson)
    }

    @Test fun `all exact matches reserved before a preceding display alias consumes identity`() {
        val oldA = profile("old-a", displayConfig("A"), order = 0)
        val oldB = profile("old-b", displayConfig("B"), order = 1)
        val alias = profile("generated-c", displayConfig("C"), order = 0)
        val exactA = oldA.copy(id = "generated-a", sourceOrder = 1)
        val result = SubscriptionProfileIdentity.reuse(listOf(oldA, oldB), listOf(alias, exactA))
        assertEquals(listOf("old-b", "old-a"), result.map { it.id })
        assertEquals(listOf(alias.sourceRaw, exactA.sourceRaw), result.map { it.sourceRaw })
        assertEquals(listOf(0, 1), result.map { it.sourceOrder })
    }

    @Test fun `duplicate multiplicity order and one to one matching are preserved`() {
        val old = listOf(profile("old-1", order = 0), profile("old-2", order = 1))
        val incoming = (0..2).map { profile("new-$it", order = it) }
        val result = SubscriptionProfileIdentity.reuse(old, incoming)
        assertEquals(listOf("old-1", "old-2", "new-2"), result.map { it.id })
        assertEquals(3, result.map { it.id }.toSet().size)
        assertEquals(incoming.map { it.sourceOrder }, result.map { it.sourceOrder })
    }

    @Test fun `routing DNS UUID key endpoint and outbound tags remain identity material`() {
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("routing").put("domainStrategy", "IPIfNonMatch") },
            { it.getJSONObject("dns").put("servers", org.json.JSONArray(listOf("8.8.8.8", "1.1.1.1"))) },
            { it.getJSONArray("outbounds").getJSONObject(0).put("tag", "different-proxy") },
            { it.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).put("address", "192.0.2.2") },
            { it.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).put("port", 8443) },
            { it.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getJSONArray("users").getJSONObject(0).put("id", "22222222-2222-4222-8222-222222222222") },
            { it.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").getJSONObject("realitySettings").put("publicKey", "different-key") }
        )
        edits.forEachIndexed { index, edit ->
            val changed = JSONObject(config).also(edit).toString()
            val result = SubscriptionProfileIdentity.reuse(listOf(profile("old")), listOf(profile("new-$index", changed))).single()
            assertEquals("new-$index", result.id)
        }
    }

    @Test fun `separate resolved DNS list remains fingerprint material for a display alias`() {
        val old = profile("old")
        val changed = profile("new", displayConfig("Alias")).copy(dnsServers = listOf("9.9.9.9"))
        assertEquals("new", SubscriptionProfileIdentity.reuse(listOf(old), listOf(changed)).single().id)
    }

    @Test fun `fingerprint cannot reuse identity from another subscription`() {
        val result = SubscriptionProfileIdentity.reuse(listOf(profile("old", parent = "sub-a")), listOf(profile("new", parent = "sub-b"))).single()
        assertEquals("new", result.id)
    }

    @Test fun `URI fragment is display only while credential query and path remain significant`() {
        val raw = "vless://11111111-1111-4111-8111-111111111111@192.0.2.1:443?type=ws&path=%2Fvpn&pbk=synthetic#Old"
        val old = profile("old", raw, config, type = ProfileType.VLESS)
        val alias = profile("new", raw.substringBefore('#') + "#New", config, type = ProfileType.VLESS)
        assertEquals("old", SubscriptionProfileIdentity.reuse(listOf(old), listOf(alias)).single().id)
        val changed = alias.copy(sourceRaw = alias.sourceRaw.replace("path=%2Fvpn", "path=%2Fother"))
        assertEquals("new", SubscriptionProfileIdentity.reuse(listOf(old), listOf(changed)).single().id)
    }

    @Test fun `VMess ps only rename keeps id without rewriting encoded source`() {
        val raw = """{"v":"2","ps":"Old","add":"192.0.2.1","port":"443","id":"11111111-1111-4111-8111-111111111111","net":"ws","path":"/vpn"}"""
        val renamed = JSONObject(raw).put("ps", "New").toString()
        fun link(value: String) = "vmess://" + Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        val incoming = profile("new", link(renamed), renamed, type = ProfileType.VMESS)
        val result = SubscriptionProfileIdentity.reuse(listOf(profile("old", link(raw), raw, type = ProfileType.VMESS)), listOf(incoming)).single()
        assertEquals("old", result.id)
        assertEquals(incoming.sourceRaw, result.sourceRaw)
        assertEquals(renamed, result.normalizedJson)
    }
}
