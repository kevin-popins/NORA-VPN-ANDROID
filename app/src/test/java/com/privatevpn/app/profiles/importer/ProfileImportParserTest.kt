package com.privatevpn.app.profiles.importer

import com.privatevpn.app.profiles.model.ProfileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class ProfileImportParserTest {

    private val parser = ProfileImportParser()

    @Test
    fun `amnezia config with H range imports successfully`() {
        val config = """
            [Interface]
            Address = 10.10.10.2/32
            DNS = 1.1.1.1, 1.0.0.1
            PrivateKey = RkRZCSxTtCLGNon7xBRYbUMike+4dAe00VXWOLc9jf0=
            Jc = 4
            Jmin = 10
            Jmax = 50
            S1 = 146
            S2 = 43
            S3 = 20
            S4 = 2
            H1 = 120446805-394029787
            H2 = 1809482896-2093846687
            H3 = 2104463974-2143295998
            H4 = 2145613584-2147264051
            I1 = <r 2><b 0x0102030405060708090a0b0c0d0e0f10>
            I2 =
            I3 =
            I4 =
            I5 =

            [Peer]
            PublicKey = 8OYCZNdco4f0Qk1kTVDncpPxa29iGPOeSSmHFBiW66A=
            PresharedKey = 9MIGLiDvFCqBIj4dMA2hx9lUPUJYmL6IZSf7m3+2oYI=
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = 198.51.100.42:51820
            PersistentKeepalive = 25
        """.trimIndent()

        val parsed = parser.parse(config)

        assertEquals(ProfileType.AMNEZIA_WG_20, parsed.type)
        assertTrue(parsed.normalizedJson?.contains("S3 = 20") == true)
        assertTrue(parsed.normalizedJson?.contains("S4 = 2") == true)
        assertTrue(parsed.importWarnings.any { it.contains("I2", ignoreCase = true) })
    }

    @Test
    fun `krot connection key imports successfully`() {
        val parsed = parser.parse(krotKey())

        assertEquals(ProfileType.KROT, parsed.type)
        assertEquals("KRot 198.51.100.42:443", parsed.displayName)
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), parsed.dnsServers)
        assertTrue(parsed.sourceRaw.startsWith("nora1."))
    }

    @Test
    fun `krot normalized json preserves connection fields`() {
        val parsed = parser.parse(krotKey())
        val normalized = parsed.normalizedJson.orEmpty()

        assertTrue(normalized.contains("\"host\": \"198.51.100.42\""))
        assertTrue(normalized.contains("\"tls_name\": \"example.com\""))
        assertTrue(normalized.contains("\"credential_id\": \"test-credential\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `krot broken base64 is rejected`() {
        parser.parse("nora1.not-base64!!")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `krot wrong schema is rejected`() {
        parser.parse(krotKey(schema = "nora-connection-key-v2"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `krot empty credential key is rejected`() {
        parser.parse(krotKey(credentialKey = ""))
    }

    private fun krotKey(
        schema: String = "nora-connection-key-v1",
        credentialKey: String = Base64.getEncoder().encodeToString(ByteArray(32) { index -> (index + 1).toByte() })
    ): String {
        val json = """
            {
              "schema": "$schema",
              "profile_id": "test-profile",
              "transport_profile": "tls_http_cover_v1",
              "server": {
                "host": "198.51.100.42",
                "port": 443,
                "tls_name": "example.com",
                "cover_host": "example.com"
              },
              "credentials": {
                "credential_id": "test-credential",
                "credential_key": "$credentialKey"
              },
              "tunnel": {
                "client_ip": "10.66.0.2",
                "server_ip": "10.66.0.1",
                "cidr": "10.66.0.0/24",
                "dns": ["1.1.1.1", "8.8.8.8"]
              }
            }
        """.trimIndent()
        val encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        return "nora1.$encoded"
    }
}
