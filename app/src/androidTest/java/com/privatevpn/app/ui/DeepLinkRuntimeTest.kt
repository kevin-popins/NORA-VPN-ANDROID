package com.privatevpn.app.ui

import android.content.Intent
import android.net.Uri
import android.app.Instrumentation
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevpn.app.MainActivity
import com.privatevpn.app.profiles.db.PrivateVpnDatabase
import com.privatevpn.app.settings.storage.DataStoreUserSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** Actual Android cold Activity + warm single-task intent delivery; synthetic inline data only. */
class DeepLinkRuntimeTest {
    @Test fun coldAndWarmNoraLinksImportOnceWithoutChangingExistingSelection() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=PrivateVpnDatabase.build(context)
        val settings=DataStoreUserSettingsRepository(context)
        val before=database.profileDao().observeAll().first()
        val oldSelection=settings.settings.first().activeProfileId
        val cold="vless://00000000-0000-4000-8000-000000000097@example.invalid:443?security=tls&type=tcp&sni=example.invalid#NORA%20COLD%20TEST"
        val warm="vless://00000000-0000-4000-8000-000000000096@example.invalid:443?security=tls&type=tcp&sni=example.invalid#NORA%20WARM%20TEST"
        fun intent(raw:String)=Intent(Intent.ACTION_VIEW,Uri.parse("noravpn://import/profile?v=1&payload="+Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())))
            .setClass(context,MainActivity::class.java)
        try {
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            val monitor=Instrumentation.ActivityMonitor(MainActivity::class.java.name,null,false)
            instrumentation.addMonitor(monitor)
            var activity:MainActivity?=null
            try {
                context.startActivity(intent(cold).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                activity=instrumentation.waitForMonitorWithTimeout(monitor,10000) as? MainActivity
                assertNotNull("Cold Activity must be created",activity)
                withTimeout(15000) { database.profileDao().observeAll().first { rows -> rows.count{it.sourceRaw==cold}==1 } }
                val coldInstance=activity
                instrumentation.runOnMainSync { coldInstance!!.startActivity(intent(warm).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                withTimeout(15000) { database.profileDao().observeAll().first { rows -> rows.count{it.sourceRaw==warm}==1 } }
                instrumentation.runOnMainSync { assertFalse("Warm activation must reuse the cold Activity",coldInstance!!.isDestroyed) }
                assertEquals("Warm intent must not create another Activity",1,monitor.hits)
                val after=database.profileDao().observeAll().first()
                assertEquals(before.size+2,after.size)
                assertTrue("Existing selection must survive import",settings.settings.first().activeProfileId==oldSelection)
                assertTrue("Existing IDs must remain",before.all{old->after.any{it.id==old.id}})
            } finally {
                instrumentation.removeMonitor(monitor)
                instrumentation.runOnMainSync { activity?.finish() }
            }
        } finally {
            database.profileDao().observeAll().first().filter { it.sourceRaw in setOf(cold,warm) && before.none{old->old.id==it.id} }
                .forEach { database.profileDao().deleteById(it.id) }
            settings.setActiveProfile(oldSelection)
            database.close()
        }
    }
}
