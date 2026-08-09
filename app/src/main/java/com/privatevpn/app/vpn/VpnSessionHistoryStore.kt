package com.privatevpn.app.vpn

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class VpnSessionRecord(
    val endedAtMs: Long,
    val durationSeconds: Long,
    val uplinkBytes: Long,
    val downlinkBytes: Long,
    val profileName: String
)

object VpnSessionHistoryStore {
    private const val PREFS = "nora_vpn_session_history"
    private const val KEY_RECORDS = "records"
    private const val LIMIT = 10
    private val _records = MutableStateFlow<List<VpnSessionRecord>>(emptyList())
    val records: StateFlow<List<VpnSessionRecord>> = _records.asStateFlow()
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        _records.value = decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RECORDS, null))
    }

    @Synchronized
    fun recordCompletedSession(context: Context, traffic: VpnTrafficState, profileName: String?) {
        val startedAt = traffic.connectedAtMs ?: return
        val endedAt = System.currentTimeMillis()
        val record = VpnSessionRecord(
            endedAtMs = endedAt,
            durationSeconds = ((endedAt - startedAt) / 1_000L).coerceAtLeast(0L),
            uplinkBytes = traffic.uplinkBytes,
            downlinkBytes = traffic.downlinkBytes,
            profileName = profileName?.takeIf { it.isNotBlank() } ?: "NORA VPN"
        )
        val updated = (listOf(record) + _records.value).take(LIMIT)
        _records.value = updated
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RECORDS, encode(updated))
            .apply()
    }

    private fun encode(records: List<VpnSessionRecord>): String = JSONArray().apply {
        records.forEach { record ->
            put(JSONObject().apply {
                put("endedAtMs", record.endedAtMs)
                put("durationSeconds", record.durationSeconds)
                put("uplinkBytes", record.uplinkBytes)
                put("downlinkBytes", record.downlinkBytes)
                put("profileName", record.profileName)
            })
        }
    }.toString()

    private fun decode(raw: String?): List<VpnSessionRecord> = runCatching {
        val array = JSONArray(raw ?: return emptyList())
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(VpnSessionRecord(
                    endedAtMs = item.optLong("endedAtMs"),
                    durationSeconds = item.optLong("durationSeconds"),
                    uplinkBytes = item.optLong("uplinkBytes"),
                    downlinkBytes = item.optLong("downlinkBytes"),
                    profileName = item.optString("profileName", "NORA VPN")
                ))
            }
        }.take(LIMIT)
    }.getOrDefault(emptyList())
}
