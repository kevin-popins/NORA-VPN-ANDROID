package com.privatevpn.app.vpn

import com.privatevpn.app.core.error.AppError
import com.privatevpn.app.core.error.AppErrors
import com.privatevpn.app.vpn.krot.KrotVpnService
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test

/** A stale callback must return before any service cleanup or global state mutation. */
class RuntimeFailureOwnershipTest {
    @Test fun staleErrorCannotTearDownTheNewRuntime() {
        assumeFalse("Do not compete with a user's active runtime", PrivateVpnService.isRunning())
        assumeFalse(VpnRuntimeStateStore.status.value in setOf(VpnConnectionStatus.CONNECTED, VpnConnectionStatus.CONNECTING))
        val owner = "synthetic-new-runtime"
        val stale = "synthetic-old-runtime"
        val originalStatus = VpnRuntimeStateStore.status.value
        for (serviceClass in listOf(PrivateVpnService::class.java, KrotVpnService::class.java)) {
            val service = serviceClass.getDeclaredConstructor().newInstance()
            val tokenField = serviceClass.getDeclaredField("activeRuntimeToken").apply { isAccessible = true }
            val handlingField = serviceClass.getDeclaredField("handlingFailure").apply { isAccessible = true }
            tokenField.set(service, owner)
            VpnRuntimeStateStore.claimRuntimeOwner(owner)
            try {
                val error = AppErrors.genericUiStateError("Synthetic failure", "Synthetic stale callback")
                serviceClass.getDeclaredMethod("failWithError", AppError::class.java, String::class.java)
                    .apply { isAccessible = true }.invoke(service, error, stale)
                assertTrue(VpnRuntimeStateStore.isRuntimeOwner(owner))
                assertEquals(owner, tokenField.get(service))
                assertFalse(handlingField.getBoolean(service))
            } finally {
                VpnRuntimeStateStore.finishRuntimeForOwner(owner, originalStatus)
            }
        }
    }
}