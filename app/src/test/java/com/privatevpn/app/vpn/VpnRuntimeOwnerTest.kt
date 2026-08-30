package com.privatevpn.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRuntimeOwnerTest {
    @Test
    fun `stale backend cannot overwrite current runtime state`() {
        VpnRuntimeStateStore.claimRuntimeOwner("old-runtime")
        assertTrue(
            VpnRuntimeStateStore.setStatusForOwner(
                "old-runtime",
                VpnConnectionStatus.CONNECTING
            )
        )

        VpnRuntimeStateStore.claimRuntimeOwner("new-runtime")
        assertTrue(
            VpnRuntimeStateStore.setStatusForOwner(
                "new-runtime",
                VpnConnectionStatus.CONNECTED
            )
        )

        assertFalse(
            VpnRuntimeStateStore.finishRuntimeForOwner(
                "old-runtime",
                VpnConnectionStatus.READY
            )
        )
        assertEquals(VpnConnectionStatus.CONNECTED, VpnRuntimeStateStore.status.value)

        assertTrue(
            VpnRuntimeStateStore.finishRuntimeForOwner(
                "new-runtime",
                VpnConnectionStatus.READY
            )
        )
    }
}
