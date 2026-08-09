package com.privatevpn.app.vpn

import android.net.TrafficStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class VpnTrafficSample(
    val elapsedSeconds: Long,
    val uplinkBytesPerSecond: Long,
    val downlinkBytesPerSecond: Long
)

data class VpnTrafficState(
    val uplinkBytes: Long = 0,
    val downlinkBytes: Long = 0,
    val uplinkBytesPerSecond: Long = 0,
    val downlinkBytesPerSecond: Long = 0,
    val connectedAtMs: Long? = null,
    val samples: List<VpnTrafficSample> = emptyList()
)

object VpnRuntimeStateStore {
    private val _status = MutableStateFlow(VpnConnectionStatus.NO_PERMISSION)
    val status: StateFlow<VpnConnectionStatus> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _appTrafficMode = MutableStateFlow(AppTrafficMode.UNKNOWN)
    val appTrafficMode: StateFlow<AppTrafficMode> = _appTrafficMode.asStateFlow()

    private val _internalDataPlanePort = MutableStateFlow<Int?>(null)
    val internalDataPlanePort: StateFlow<Int?> = _internalDataPlanePort.asStateFlow()

    private val _lastSelectedProfileName = MutableStateFlow<String?>(null)
    val lastSelectedProfileName: StateFlow<String?> = _lastSelectedProfileName.asStateFlow()

    private val _traffic = MutableStateFlow(VpnTrafficState())
    val traffic: StateFlow<VpnTrafficState> = _traffic.asStateFlow()
    private val trafficScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var trafficSampler: Job? = null

    fun setStatus(status: VpnConnectionStatus) {
        _status.value = status
        if (status != VpnConnectionStatus.ERROR) {
            _lastError.value = null
        }
    }

    fun setError(message: String) {
        _status.value = VpnConnectionStatus.ERROR
        _lastError.value = message
    }

    fun clearError() {
        _lastError.value = null
    }

    fun setAppTrafficMode(mode: AppTrafficMode) {
        _appTrafficMode.value = mode
    }

    fun setInternalDataPlanePort(port: Int?) {
        _internalDataPlanePort.value = port
    }

    fun setLastSelectedProfileName(profileName: String?) {
        _lastSelectedProfileName.value = profileName?.trim()?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun startTrafficSampling(uid: Int) {
        stopTrafficSampling()
        val startedAt = System.currentTimeMillis()
        _traffic.value = VpnTrafficState(connectedAtMs = startedAt)
        trafficSampler = trafficScope.launch {
            var previousTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
            var previousRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
            var previousAt = System.currentTimeMillis()
            while (isActive) {
                delay(1_000)
                val now = System.currentTimeMillis()
                val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
                val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
                val elapsedMs = (now - previousAt).coerceAtLeast(1L)
                val upDelta = (tx - previousTx).coerceAtLeast(0L)
                val downDelta = (rx - previousRx).coerceAtLeast(0L)
                val prior = _traffic.value
                val seconds = ((now - startedAt) / 1_000L).coerceAtLeast(0L)
                _traffic.value = prior.copy(
                    uplinkBytes = prior.uplinkBytes + upDelta,
                    downlinkBytes = prior.downlinkBytes + downDelta,
                    uplinkBytesPerSecond = upDelta * 1_000L / elapsedMs,
                    downlinkBytesPerSecond = downDelta * 1_000L / elapsedMs,
                    samples = (prior.samples + VpnTrafficSample(
                        elapsedSeconds = seconds,
                        uplinkBytesPerSecond = upDelta * 1_000L / elapsedMs,
                        downlinkBytesPerSecond = downDelta * 1_000L / elapsedMs
                    )).takeLast(MAX_TRAFFIC_SAMPLES)
                )
                previousTx = tx
                previousRx = rx
                previousAt = now
            }
        }
    }

    @Synchronized
    fun stopTrafficSampling() {
        trafficSampler?.cancel()
        trafficSampler = null
        _traffic.value = VpnTrafficState()
    }

    private const val MAX_TRAFFIC_SAMPLES = 48
}
