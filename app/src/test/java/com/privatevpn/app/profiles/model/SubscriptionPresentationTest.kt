package com.privatevpn.app.profiles.model

import org.junit.Assert.*
import org.junit.Test

class SubscriptionPresentationTest {
    private fun source(id: String, protection: Boolean, label: String? = "NORA private") = SubscriptionSource(
        id, "Subscription", "https://subs.example.invalid/synthetic-token", SubscriptionSourceType.HTTP_TEXT,
        true, true, 60, null, null, null, false, 1, null, null,
        SubscriptionMetadataCodec.encode(SubscriptionMetadataPayload(clientPolicy = SubscriptionClientPolicy(`protected` = protection, publicLabel = label))),
        emptyList(), null, SubscriptionSyncStatus.SUCCESS, 1L, 1L
    )
    private fun profile(parent: String? = "a", name: String = "Europe") = VpnProfile(
        "synthetic-id", name, ProfileType.VLESS,
        "vless://synthetic-user@proxy.example.invalid:443#Europe",
        """{"outbounds":[{"settings":{"vnext":[{"address":"proxy.example.invalid","port":443}]}}]}""",
        listOf("resolver.example.invalid"), false, false, emptyList(), 1L, parent, 3
    )
    @Test fun policyIsScopedAndRawIdentityIsUnchanged() {
        val original = profile()
        val sources = listOf(source("a", true), source("b", false))
        val display = SubscriptionPresentation.apply(original, sources)
        assertTrue(display.endpointProtectionEnabled)
        assertEquals(original.displayName, display.userFacingName)
        assertEquals(original.id, display.id)
        assertEquals(original.sourceRaw, display.sourceRaw)
        assertEquals(original.normalizedJson, display.normalizedJson)
        assertEquals(original.sourceOrder, display.sourceOrder)
        assertFalse(SubscriptionPresentation.apply(profile("b"), sources).endpointProtectionEnabled)
        assertFalse(SubscriptionPresentation.apply(profile(null), sources).endpointProtectionEnabled)
    }
    @Test fun endpointAndParentSourceNamesNeverBecomePublicTitles() {
        val sources = listOf(source("a", true))
        assertEquals("NORA private", SubscriptionPresentation.apply(profile(name="proxy.example.invalid"), sources).userFacingName)
        assertEquals("NORA private", SubscriptionPresentation.apply(profile(name="subs.example.invalid/synthetic-token"), sources).userFacingName)
    }
    @Test fun fullMaterialIsRemovedBeforeShorterHostMatches() {
        val original = profile()
        val s = source("a", true)
        val redact = SubscriptionPresentation.redactor(listOf(original), listOf(s))
        val result = redact("${original.sourceRaw} ${original.normalizedJson} ${s.sourceUrl}")
        assertFalse(result.contains("synthetic-user"))
        assertFalse(result.contains("synthetic-token"))
        assertFalse(result.contains("proxy.example.invalid"))
        assertFalse(result.contains("vnext"))
    }
    @Test fun publicLabelCannotItselfRevealAnAddress() {
        listOf<String?>(null, "", "https://subs.example.invalid/synthetic-token", "proxy.example.invalid").forEach {
            assertEquals("Защищённый сервер", SubscriptionClientPolicy(`protected`=true, publicLabel=it).publicDisplayLabel)
        }
    }
    @Test fun expiryNeverUnlocksAcceptedProtection() {
        assertTrue(SubscriptionClientPolicy(`protected`=true,expiresAtMs=1).endpointProtectionEnabled)
    }
    @Test fun internalPolicyRoundTripsWithoutSchemaMigration() {
        val policy = SubscriptionClientPolicy(`protected`=true,publicLabel="NORA private",issuedAtMs=1000,revision=2,expiresAtMs=5000)
        val encoded = SubscriptionMetadataCodec.encode(SubscriptionMetadataPayload(clientPolicy=policy))
        assertEquals(policy,SubscriptionMetadataCodec.decode(encoded).clientPolicy)
    }
    @Test fun literalNullTextIsNotJsonNull() {
        val literal = SubscriptionClientPolicy(`protected`=true,publicLabel="null")
        val absent = SubscriptionClientPolicy(`protected`=true,publicLabel=null)
        assertEquals(literal,SubscriptionMetadataCodec.decode(SubscriptionMetadataCodec.encode(SubscriptionMetadataPayload(clientPolicy=literal))).clientPolicy)
        assertEquals(absent,SubscriptionMetadataCodec.decode(SubscriptionMetadataCodec.encode(SubscriptionMetadataPayload(clientPolicy=absent))).clientPolicy)
    }
}
