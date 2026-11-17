package com.privatevpn.app.profiles.subscriptions

import com.privatevpn.app.profiles.importer.ProfileImportParser
import com.privatevpn.app.profiles.model.ProfileType
import com.privatevpn.app.profiles.model.VpnProfile
import org.junit.Assert.*
import org.junit.Test

class SubscriptionBodyValidatorTest {
    @Test fun `Xray compatibility DNS inbound warnings do not reject valid transport`() {
        val raw = """{"remarks":"Synthetic","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"192.0.2.1","port":443,"users":[{"id":"11111111-1111-4111-8111-111111111111"}]}]}}]}"""
        val draft = ProfileImportParser().parse(raw)
        assertTrue(draft.isPartialImport)
        assertTrue(SubscriptionBodyValidator.isComplete(listOf(VpnProfile("id", draft.displayName, draft.type, draft.sourceRaw,
            draft.normalizedJson, draft.dnsServers, draft.dnsFallbackApplied, draft.isPartialImport, draft.importWarnings, 0))))
    }
    @Test fun `malformed partial URI and JSON without a proxy cannot authorize a clear`() {
        fun profile(raw: String, normalized: String?) = VpnProfile("id", "Synthetic", ProfileType.VLESS, raw,
            normalized, emptyList(), false, true, emptyList(), 0)
        assertFalse(SubscriptionBodyValidator.isComplete(listOf(profile("vless://bad", null))))
        assertFalse(SubscriptionBodyValidator.isComplete(listOf(profile("{}", "{}"))))
        assertFalse(SubscriptionBodyValidator.isComplete(listOf(profile("{}", """{"outbounds":[{"protocol":"freedom"}]}"""))))
    }
}
