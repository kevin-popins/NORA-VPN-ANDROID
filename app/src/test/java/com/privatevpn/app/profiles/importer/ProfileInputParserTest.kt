package com.privatevpn.app.profiles.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

class ProfileInputParserTest {
    private val parser = ProfileInputParser()

    @Test fun `typed pasted wrapped and deep linked URLs preserve identical payload`() {
        val url = "HTTPS://example.com/sub/AbC%2FToken?value=a+b&path=%2f%2F"
        val inputs = listOf(url, " \uFEFF$url\r\n", "<$url>", "\"$url\"", "[Открыть]($url)",
            "noravpn://import/subscription?url=${URLEncoder.encode(url, StandardCharsets.UTF_8)}")
        inputs.forEach { input ->
            val request = parser.parse(input) as ProfileInput.Subscription
            assertEquals(url, request.url)
        }
    }

    @Test fun `manual and deep link profile share the same parser and raw config`() {
        val config = """{"remarks":"Synthetic","inbounds":[],"outbounds":[{"protocol":"freedom"}],"dns":{"servers":["1.1.1.1"]}}"""
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(config.toByteArray(StandardCharsets.UTF_8))
        val direct = (parser.parse(config) as ProfileInput.Profiles).drafts.single()
        val link = (parser.parse("noravpn://import/profile/$encoded?v=1") as ProfileInput.Profiles).drafts.single()
        assertEquals(direct, link)
        assertEquals(config, direct.sourceRaw)
    }

    @Test fun `base64 subscription body imports every JSON profile in order`() {
        val json = """[{"remarks":"First","outbounds":[]},{"remarks":"Second","outbounds":[]}]"""
        val payload = Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        val parsed = parser.parse(payload) as ProfileInput.Profiles
        assertEquals(listOf("First", "Second"), parsed.drafts.map { it.displayName })
    }

    @Test fun `crypt5 resolving to subscription follows the shared URL validation`() {
        val encryptedParser = ProfileInputParser(decryptCrypt5 = { "https://example.com/sub/Stable?x=a%2Bb" })
        val parsed = encryptedParser.parse("<happ://crypt5/synthetic>", "My subscription") as ProfileInput.Subscription
        assertEquals("https://example.com/sub/Stable?x=a%2Bb", parsed.url)
        assertEquals("My subscription", parsed.displayName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `recursive crypt5 wrappers are bounded`() {
        ProfileInputParser(decryptCrypt5 = { it }).parse("happ://crypt5/synthetic")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `profile deep link cannot bypass declared type with subscription URL`() {
        parser.parse("noravpn://import/profile?data=https%3A%2F%2Fexample.com%2Fsub")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `inner zero width character is rejected rather than silently changing token`() {
        parser.parse("https://example.com/sub/Token\u200B")
    }

    @Test fun `normalization does not remove punctuation inside a complete URI`() {
        val url = "https://example.com/sub/(Token)?value=Hi!"
        assertEquals(url, ImportInputNormalizer.normalize("<$url>"))
        assertTrue(ImportInputNormalizer.normalize("prefix $url").startsWith("prefix "))
    }
}
