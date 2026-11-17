package com.privatevpn.app.profiles.model

import org.junit.Assert.*
import org.junit.Test

class ProtectedAnnouncementTest {
    private val instruction = "Нажмите 🔄 и поменяйте сервер если не работает."
    private fun source(announcement: String?, protected: Boolean = true) = SubscriptionSource(
        id = "synthetic-source", displayName = "Synthetic subscription", sourceUrl = "https://subs.example.invalid/synthetic",
        sourceType = SubscriptionSourceType.HTTP_TEXT, enabled = true, autoUpdateEnabled = true,
        updateIntervalMinutes = 60, lastUpdatedAtMs = null, lastSuccessAtMs = null, lastError = null,
        isCollapsed = false, profileCount = 1, etag = null, lastModified = null,
        metadata = SubscriptionMetadataCodec.encode(SubscriptionMetadataPayload(
            announcementText = announcement,
            clientPolicy = SubscriptionClientPolicy(`protected` = protected, publicLabel = "NORA private"))),
        childProfileIds = listOf("synthetic-profile"), lastSelectedProfileId = "synthetic-profile",
        syncStatus = SubscriptionSyncStatus.SUCCESS, createdAtMs = 1, updatedAtMs = 1
    )
    private fun profile(parent: String = "synthetic-source", host: String = "private-host") = VpnProfile(
        id = "synthetic-profile", displayName = "Europe", type = ProfileType.VLESS,
        sourceRaw = "vless://synthetic-user@$host:443#Europe", normalizedJson = null,
        dnsServers = emptyList(), dnsFallbackApplied = false, isPartialImport = false,
        importWarnings = emptyList(), importedAtMs = 1, parentSubscriptionId = parent, sourceOrder = 0
    )

    @Test fun exactPublicInstructionAndEmojiSurviveWithoutChangingIdentity() {
        val source = source(instruction)
        val profile = profile()
        val sourceBefore = source.copy()
        val profileBefore = profile.copy()
        assertEquals(instruction, SubscriptionPresentation.protectedAnnouncement(source, listOf(profile)))
        assertEquals("NORA private", SubscriptionPresentation.apply(profile, listOf(source)).protectedPublicLabel)
        assertEquals(sourceBefore, source)
        assertEquals(profileBefore, profile)
    }

    @Test fun onlyAnnounceIsAllowedWithoutOtherMetadataFallbacks() {
        val source = source(null).copy(metadata = SubscriptionMetadataCodec.encode(SubscriptionMetadataPayload(
            providerMessage = instruction, note = instruction, labelLine = instruction, tags = listOf(instruction),
            badgeText = instruction, clientPolicy = SubscriptionClientPolicy(`protected` = true, publicLabel = instruction))))
        assertNull(SubscriptionPresentation.protectedAnnouncement(source, emptyList()))
    }

    @Test fun unprotectedAndMissingAnnouncementsDoNotUseProtectedOverride() {
        assertNull(SubscriptionPresentation.protectedAnnouncement(source(instruction, protected = false), emptyList()))
        assertNull(SubscriptionPresentation.protectedAnnouncement(source(null), emptyList()))
        assertNull(SubscriptionPresentation.protectedAnnouncement(source("  "), emptyList()))
    }

    @Test fun networkAddressesAndSourceMaterialRejectTheWholeAnnouncement() {
        val profile = profile()
        for (unsafe in listOf(
            "Откройте https://subs.example.invalid/synthetic", "Адрес proxy.example.invalid",
            "Адрес секрет.рф", "Адрес 203.0.113.10", "Адрес [2001:db8::1]", "Адрес private-host",
            profile.sourceRaw, "server=private-host")) {
            assertNull(unsafe, SubscriptionPresentation.protectedAnnouncement(source(unsafe), listOf(profile)))
        }
    }

    @Test fun configCredentialsAndOpaquePayloadsRejectTheWholeAnnouncement() {
        for (unsafe in listOf(
            """{"outbounds":[]}""", "[Interface] PrivateKey=synthetic", "-----BEGIN PRIVATE KEY-----",
            "UUID 00000000-0000-4000-8000-000000000098", "password=synthetic", "token: synthetic",
            "ID: synthetic", "user_id=synthetic", "pass=synthetic", "PresharedKey=short", "shortId=0123456789abcdef", "sid=short", "pbk=short", "ss:short", "data:short", "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFB", "C:\\Users\\synthetic\\profile.json",
            "<b>Нажмите обновить</b>")) {
            assertNull(unsafe, SubscriptionPresentation.protectedAnnouncement(source(unsafe), emptyList()))
        }
    }

    @Test fun controlsBidiSurrogatesAndOversizedTextStayHiddenWithoutTruncation() {
        for (unsafe in listOf("Нажмите\nобновить", "Нажмите\u202Eобновить", "Нажмите\u200Bобновить",
            "Нажмите\uD800обновить", "Совет ".repeat(350), "🔄".repeat(1100))) {
            assertNull(SubscriptionPresentation.protectedAnnouncement(source(unsafe), emptyList()))
        }
    }

    @Test fun knownEndpointMatchingIsScopedToThisSubscriptionsChildren() {
        val source = source("Поменяйте private-host")
        assertNull(SubscriptionPresentation.protectedAnnouncement(source, listOf(profile())))
        assertEquals("Поменяйте private-host", SubscriptionPresentation.protectedAnnouncement(source, listOf(profile(parent = "other-source"))))
    }

    @Test fun benignWhitespaceAndOrdinaryPublicWordsRemainLiteral() {
        assertEquals(instruction, SubscriptionPresentation.protectedAnnouncement(source("  $instruction  "), emptyList()))
        val public = "Если сервер не работает, нажмите обновление и выберите другой."
        assertEquals(public, SubscriptionPresentation.protectedAnnouncement(source(public), emptyList()))
    }
    @Test fun standaloneShortSourceCredentialsNeverBecomePublicAnnouncements() {
        for ((url, secret) in listOf(
            "https://short-user:short-pass@subs.example.invalid/sub/short-path" to "short-user",
            "https://short-user:short-pass@subs.example.invalid/sub/short-path" to "short-pass",
            "https://subs.example.invalid/sub/short-path" to "short-path",
            "https://subs.example.invalid/sub/short-path/happ" to "short-path",
            "https://subs.example.invalid/sub/demo?token=short-query" to "short-query")) {
            val original = source("Совет $secret").copy(sourceUrl = url)
            assertNull(SubscriptionPresentation.protectedAnnouncement(original, emptyList()))
        }
    }

    @Test fun standaloneShortChildSecretsAreMatchedWithoutChangingOtherSubscriptions() {
        val secret = "short-value"
        val source = source("Совет $secret")
        val uriProfile = profile().copy(sourceRaw = "trojan://$secret@private-host:443#Europe")
        assertNull(SubscriptionPresentation.protectedAnnouncement(source, listOf(uriProfile)))
        for (key in listOf("password", "pass", "privateKey", "presharedKey", "shortId", "pbk", "token", "id")) {
            val jsonProfile = profile().copy(sourceRaw = "synthetic-local-only",
                normalizedJson = """{"settings":{"$key":"$secret"}}""")
            assertNull(key, SubscriptionPresentation.protectedAnnouncement(source, listOf(jsonProfile)))
            assertEquals("Совет $secret", SubscriptionPresentation.protectedAnnouncement(source,
                listOf(jsonProfile.copy(parentSubscriptionId = "other-source"))))
        }
        val wgProfile = profile().copy(sourceRaw = "PrivateKey=$secret")
        assertNull(SubscriptionPresentation.protectedAnnouncement(source, listOf(wgProfile)))
    }
    @Test fun rawAndOnceDecodedShortPercentEncodedSecretsBothStayHidden() {
        for ((url, raw, decoded) in listOf(
            Triple("https://short%2Buser:short%2Bpass@subs.example.invalid/sub/demo", "short%2Buser", "short+user"),
            Triple("https://short%2Buser:short%2Bpass@subs.example.invalid/sub/demo", "short%2Bpass", "short+pass"),
            Triple("https://subs.example.invalid/sub/short%2Bpath/happ", "short%2Bpath", "short+path"),
            Triple("https://subs.example.invalid/sub/demo?token=short%2Bquery", "short%2Bquery", "short+query"))) {
            for (text in listOf(raw, decoded)) {
                assertNull(SubscriptionPresentation.protectedAnnouncement(source("Совет $text").copy(sourceUrl = url), emptyList()))
            }
        }
        val profile = profile().copy(sourceRaw = "trojan://short%2Bprofile@private-host:443#Europe")
        for (text in listOf("short%2Bprofile", "short+profile")) {
            assertNull(SubscriptionPresentation.protectedAnnouncement(source("Совет $text"), listOf(profile)))
        }
    }
}