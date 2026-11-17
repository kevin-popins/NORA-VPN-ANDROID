package com.privatevpn.app.ui.location

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoraBackgroundAssetsTest {
    private val root: File
        get() = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "assets/backgrounds/manifest.json").isFile }
            ?: error("Cannot find the checked-in offline background asset manifest")

    @Test
    fun `all six new offline scenes are selected by their region and match the pinned manifest`() {
        val manifest = JSONObject(File(root, "assets/backgrounds/manifest.json").readText())
        val assets = manifest.getJSONArray("assets")
        val entries = (0 until assets.length()).associate { index ->
            val entry = assets.getJSONObject(index)
            entry.getString("resource") to entry
        }
        assertEquals(58, assets.length())
        assertEquals(assets.length(), entries.size)
        assertEquals(assets.length(), manifest.getInt("asset_count"))
        assertEquals("11.3.0", manifest.getJSONObject("encoder").getString("pillow"))
        assertEquals("1.5.0", manifest.getJSONObject("encoder").getString("libwebp"))

        val backgrounds = listOf("Bermuda", "Ватикан").flatMap { name ->
            requireNotNull(resolveNoraRegion(name)).backgroundNames
        }
        assertEquals(
            listOf("bermuda1", "bermuda2", "bermuda3", "vatican1", "vatican2", "vatican3"),
            backgrounds
        )
        backgrounds.forEach { name ->
            val resource = "nora_location_$name"
            val entry = requireNotNull(entries[resource])
            assertEquals("assets/backgrounds/masters/$resource.png", entry.getString("source"))
            assertEquals("app/src/main/res/drawable-nodpi/$resource.webp", entry.getString("output"))
            assertEquals(1672, entry.getInt("width"))
            assertEquals(941, entry.getInt("height"))
            assertEquals("RGB", entry.getString("mode"))
            val parameters = entry.getJSONObject("parameters")
            assertEquals(95, parameters.getInt("quality"))
            assertEquals(6, parameters.getInt("method"))
            assertFalse(parameters.getBoolean("lossless"))
            assertTrue(parameters.getBoolean("exact"))
            listOf("source", "output").forEach { field ->
                val file = File(root, entry.getString(field))
                assertTrue(file.path, file.isFile)
                assertEquals(file.path, entry.getLong("${field}_bytes"), file.length())
                assertEquals(file.path, entry.getString("${field}_sha256"), sha256(file))
            }
            assertFalse(File(root, "app/src/main/res/drawable-nodpi/$resource.png").exists())
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
