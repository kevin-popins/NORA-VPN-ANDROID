package com.privatevpn.app.core.backend.xray

import com.privatevpn.app.core.backend.config.*
import com.privatevpn.app.core.backend.runtime.*
import com.privatevpn.app.profiles.model.*
import com.privatevpn.app.settings.SocksSettings
import com.privatevpn.app.vpn.tunnel.*
import org.junit.Assert.*
import org.junit.Test

class TechnicalEntryDialGuardTest {
    private fun profile()=VpnProfile("synthetic-technical","Exact panel text",ProfileType.VLESS,
        "synthetic-display-entry",null,emptyList(),false,false,emptyList(),1,isTechnicalEntry=true)
    @Test fun technicalEntryStopsBeforeNormalizationRuntimeAndTunnel() {
        var touched=0
        val adapter=XrayBackendAdapter(
            object:ConfigNormalizer { override fun normalize(profile:VpnProfile):NormalizedConfig { touched++;error("Must not normalize a display entry") } },
            object:RuntimeConfigPreparer { override fun prepare(input:RuntimePreparationInput):RuntimePreparationResult { touched++;error("Must not prepare runtime") } },
            object:VpnTunnelLifecycle {
                override fun connect(request:TunnelConnectRequest):Result<Unit> { touched++;return Result.success(Unit) }
                override fun disconnect()=Result.success(Unit)
            })
        val result=adapter.start(profile(),emptyList(),false,emptySet(),SocksSettings())
        assertTrue(result.isFailure)
        assertEquals(0,touched)
    }
    @Test fun ordinaryBlackholeRoutingIsNotATechnicalServer() {
        val raw="""{"outbounds":[{"protocol":"blackhole","tag":"block"}]}"""
        val profile=VpnProfile("synthetic-routing","Routing",ProfileType.XRAY_JSON,raw,raw,emptyList(),false,false,emptyList(),1)
        assertFalse(profile.isTechnicalEntry)
        assertTrue(profile.isConnectable)
    }
}
