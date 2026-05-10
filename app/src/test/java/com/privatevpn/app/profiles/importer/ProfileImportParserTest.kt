package com.privatevpn.app.profiles.importer

import com.privatevpn.app.profiles.model.ProfileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

}
