package com.privatevpn.app.ui.location

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevpn.app.R
import com.privatevpn.app.profiles.db.PrivateVpnDatabase
import com.privatevpn.app.profiles.importer.ProfileImportParser
import com.privatevpn.app.profiles.model.VpnProfile
import com.privatevpn.app.profiles.repository.RoomProfilesRepository
import com.privatevpn.app.profiles.subscriptions.SubscriptionParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Uses synthetic imports and a separate database; never touches the user's profiles or VPN. */
class NoraAssetsAndImportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun allPackagedLocationImagesDecodeOffline() {
        val images = R.drawable::class.java.fields.filter { it.name.startsWith("nora_location_") }
        assertEquals("52 preserved backgrounds plus six Bermuda/Vatican frames", 58, images.size)
        var totalMs = 0L
        for (image in images) {
            val start = SystemClock.elapsedRealtime()
            val bitmap = BitmapFactory.decodeResource(context.resources, image.getInt(null),
                BitmapFactory.Options().apply { inScaled = false })
            assertNotNull(image.name, bitmap)
            assertTrue(image.name, bitmap.width > 0 && bitmap.height > 0)
            val elapsed = SystemClock.elapsedRealtime() - start
            totalMs += elapsed
            Log.i("NoraAssetQA", "${image.name}: ${bitmap.width}x${bitmap.height}, ${elapsed}ms, ${bitmap.allocationByteCount} bytes decoded")
            bitmap.recycle()
        }
        Log.i("NoraAssetQA", "Decoded ${images.size} offline images in ${totalMs}ms")
        val aq = resolveNoraRegion("NORA 🇸🇴 Антарктида 01")!!
        assertEquals((1..5).map { "antarctica$it" }, aq.backgroundNames)
        val ids = aq.backgroundNames.map {
            context.resources.getIdentifier("nora_location_$it", "drawable", context.packageName)
        }
        assertTrue(ids.all { it != 0 })
        assertEquals(5, ids.distinct().size)
    }

    @Test
    fun subscriptionImportAndDatabaseReopenKeepRawTransportAndIdentity() = runBlocking {
        val rawName = "NORA 🇸🇴 Антарктида 01"
        val raw = "vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443?security=tls&type=ws&path=%2Fqa&sni=example.invalid#${Uri.encode(rawName)}"
        val parser = ProfileImportParser()
        val draft = parser.parse(raw)
        assertEquals(raw, draft.sourceRaw)
        assertEquals(rawName, draft.displayName)
        assertEquals("AQ", resolveNoraRegion(draft.displayName)?.isoCode)
        val renamedLink = raw.substringBefore('#') + "#" + Uri.encode("NORA 🇦🇶 Антарктида 01")
        assertEquals(draft.normalizedJson, parser.parse(renamedLink).normalizedJson)
        val subscription = SubscriptionParser(parser)
        assertEquals(draft, subscription.parse(raw).validProfiles.single())
        assertEquals(draft, subscription.parse(raw).validProfiles.single())
        val profile = VpnProfile("qa-stable-id", draft.displayName, draft.type, draft.sourceRaw,
            draft.normalizedJson, draft.dnsServers, draft.dnsFallbackApplied, draft.isPartialImport,
            draft.importWarnings, 1L, "qa-subscription", 0)
        val databaseName = "nora-aq-qa-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, PrivateVpnDatabase::class.java, databaseName).build()
        try {
            RoomProfilesRepository(database.profileDao()).addProfile(profile)
            database.close()
            database = Room.databaseBuilder(context, PrivateVpnDatabase::class.java, databaseName).build()
            val restored = RoomProfilesRepository(database.profileDao()).getProfile(profile.id)
            assertEquals(profile, restored)
            assertEquals("AQ", resolveNoraRegion(restored!!.displayName)?.isoCode)
            assertEquals(raw, restored.sourceRaw)
            assertEquals(draft.normalizedJson, restored.normalizedJson)
            assertEquals("SO", resolveNoraRegion("NORA 🇸🇴 Somalia 01")?.isoCode)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
