package com.privatevpn.app.profiles.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class NoraDeepLinkParserTest {

    @Test
    fun `subscription query preserves nested url and optional name`() {
        val subscriptionUrl = "https://example.com/sub/token?client=android&mode=full"
        val rawUri = "noravpn://import/subscription?v=1&url=${encodeQuery(subscriptionUrl)}&name=${encodeQuery("NORA Test")}"

        val result = NoraDeepLinkParser.parse(rawUri) as NoraDeepLinkImport.Subscription

        assertEquals(subscriptionUrl, result.url)
        assertEquals("NORA Test", result.displayName)
    }

    @Test
    fun `profile query preserves plus signs and fragment`() {
        val profile = "vless://user+tag@example.com:443?pbk=abc+def&type=xhttp#NORA NL"
        val rawUri = "noravpn://import/profile?v=1&data=${encodeQuery(profile)}"

        val result = NoraDeepLinkParser.parse(rawUri) as NoraDeepLinkImport.Profile

        assertEquals(profile, result.payload)
    }

    @Test
    fun `form encoded query treats plus as space`() {
        val rawUri = "noravpn://import/subscription?v=1&url=https%3A%2F%2Fexample.com%2Fsub&name=NORA+Test"

        val result = NoraDeepLinkParser.parse(rawUri) as NoraDeepLinkImport.Subscription

        assertEquals("NORA Test", result.displayName)
    }

    @Test
    fun `profile can be imported from base64url path`() {
        val profile = "nora1.test-payload_123"
        val rawUri = "noravpn://import/profile/${base64Url(profile)}?v=1"

        val result = NoraDeepLinkParser.parse(rawUri) as NoraDeepLinkImport.Profile

        assertEquals(profile, result.payload)
    }

    @Test
    fun `subscription can be imported from base64url query payload`() {
        val subscriptionUrl = "https://example.com/sub?a=1&b=2"
        val rawUri = "noravpn://import/subscription?v=1&payload=${base64Url(subscriptionUrl)}"

        val result = NoraDeepLinkParser.parse(rawUri) as NoraDeepLinkImport.Subscription

        assertEquals(subscriptionUrl, result.url)
        assertNull(result.displayName)
    }

    @Test
    fun `scheme and host detection is case insensitive`() {
        assertTrue(NoraDeepLinkParser.isNoraDeepLink("NORAVPN://IMPORT/profile?data=nora1.test"))
        assertTrue(NoraDeepLinkParser.isNoraDeepLink("noravpn://import/profile?data=vless%ZZtest"))
        assertFalse(NoraDeepLinkParser.isNoraDeepLink("https://example.com/import/profile"))
        assertFalse(NoraDeepLinkParser.isNoraDeepLink("noravpn://other/profile?data=nora1.test"))
        assertFalse(NoraDeepLinkParser.isNoraDeepLink("noravpn://importer/profile?data=nora1.test"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate payload parameters are rejected`() {
        NoraDeepLinkParser.parse("noravpn://import/profile?data=nora1.one&data=nora1.two")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mixed path and query payloads are rejected`() {
        NoraDeepLinkParser.parse(
            "noravpn://import/profile/${base64Url("nora1.one")}?data=nora1.two"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown version is rejected`() {
        NoraDeepLinkParser.parse("noravpn://import/profile?v=2&data=nora1.test")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed percent encoding is rejected`() {
        NoraDeepLinkParser.parse("noravpn://import/profile?data=vless%ZZtest")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed base64url is rejected`() {
        NoraDeepLinkParser.parse("noravpn://import/profile/not+base64")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non http subscription url is rejected`() {
        val rawUri = "noravpn://import/subscription?url=${encodeQuery("file:///data/local/tmp/sub")}"
        NoraDeepLinkParser.parse(rawUri)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `subscription url with embedded credentials is rejected`() {
        val rawUri = "noravpn://import/subscription?url=${encodeQuery("https://user:pass@example.com/sub")}"
        NoraDeepLinkParser.parse(rawUri)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `forbidden control character is rejected`() {
        NoraDeepLinkParser.parse("noravpn://import/profile?data=nora1.test%00tail")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized profile payload is rejected`() {
        val payload = "a".repeat(256 * 1024 + 1)
        NoraDeepLinkParser.parse("noravpn://import/profile?data=$payload")
    }

    private fun encodeQuery(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
            .replace("+", "%20")
    }

    private fun base64Url(value: String): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }
}
