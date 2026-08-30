package com.privatevpn.app.ui.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoraRegionResolverTest {

    @Test
    fun `recognizes Estonia in Russian English ISO and city forms`() {
        assertRegion("EE", "NORA ЭСТОНИЯ 01")
        assertRegion("EE", "fast-estonia-vless")
        assertRegion("EE", "NORA ee 4G")
        assertRegion("EE", "Tallinn premium")
        assertRegion("EE", "Сервер в Эстонии")
        assertRegion("EE", "Estonian node")
    }

    @Test
    fun `recognizes Bulgaria in Russian English ISO and city forms`() {
        assertRegion("BG", "NORA Болгария")
        assertRegion("BG", "BULGARIA LTE")
        assertRegion("BG", "bg-02")
        assertRegion("BG", "BGR direct")
        assertRegion("BG", "Bulgarian direct")
        assertRegion("BG", "Sofia\nReality")
        assertRegion("BG", "Болгарский сервер Варна")
    }

    @Test
    fun `recognizes other new European locations and city aliases`() {
        assertRegion("FI", "Helsinki FIN")
        assertRegion("LV", "NORA RIGA")
        assertRegion("SK", "Bratislava premium")
        assertRegion("HR", "HR Zagreb")
        assertRegion("MD", "Кишинёв MD")
    }

    @Test
    fun `recognition ignores case separators line breaks and diacritics`() {
        assertRegion("NL", "FirstDVS\nAmStErDaM")
        assertRegion("NL", "NORA НЛ 2")
        assertRegion("DE", "NORA-MÜNCHEN-01")
        assertRegion("PL", "KRAKÓW / POL")
        assertRegion("CH", "Zürich private")
    }

    @Test
    fun `multiple aliases of the same country remain unambiguous`() {
        assertRegion("BG", "BG Bulgaria Sofia")
        assertRegion("EE", "EE Estonia Tallinn 🇪🇪")
    }

    @Test
    fun `conflicting countries never select a region`() {
        assertNull(resolveNoraRegion("ГЕРМАНИЯ НИДЕРЛАНДЫ"))
        assertNull(resolveNoraRegion("NL / DE"))
        assertNull(resolveNoraRegion("Sofia Tallinn"))
        assertNull(resolveNoraRegion("🇧🇬 🇪🇪 mixed route"))
    }

    @Test
    fun `short aliases only match complete tokens`() {
        assertNull(resolveNoraRegion("design-server"))
        assertNull(resolveNoraRegion("catalog-bgtest"))
        assertRegion("BG", "catalog BG test")
    }

    @Test
    fun `common lowercase words are not mistaken for ISO codes in descriptive names`() {
        assertRegion("DE", "server at Germany")
        assertRegion("FR", "service in France")
        assertRegion("NO", "NO Oslo")
    }

    @Test
    fun `unknown name has no region`() {
        assertNull(resolveNoraRegion("NORA FAST PREMIUM 01"))
    }

    @Test
    fun `Bulgaria exposes all three crossfade backgrounds`() {
        assertEquals(
            listOf("bulgaria1", "bulgaria2", "bulgaria3"),
            resolveNoraRegion("Bulgaria")?.backgroundNames
        )
    }

    private fun assertRegion(expectedIsoCode: String, profileName: String) {
        assertEquals(expectedIsoCode, resolveNoraRegion(profileName)?.isoCode)
    }
}
