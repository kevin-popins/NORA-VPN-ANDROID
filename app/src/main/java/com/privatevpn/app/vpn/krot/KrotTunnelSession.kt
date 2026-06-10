package com.privatevpn.app.vpn.krot

import android.os.ParcelFileDescriptor
import com.privatevpn.app.core.backend.krot.KrotConnectionSpec
import com.privatevpn.app.core.backend.krot.KrotFrameType
import com.privatevpn.app.core.backend.krot.KrotProtocol
import com.privatevpn.app.core.backend.krot.KrotTransportSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class KrotTunnelSession(
    private val spec: KrotConnectionSpec,
    private val establishTunInterface: () -> ParcelFileDescriptor,
    private val protectSocket: (Socket) -> Boolean,
    private val log: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = AtomicBoolean(true)
    private val failureReported = AtomicBoolean(false)
    private val tunToRelayPackets = AtomicLong(0)
    private val relayToTunPackets = AtomicLong(0)
    private val tunToRelayBytes = AtomicLong(0)
    private val relayToTunBytes = AtomicLong(0)

    @Volatile
    private var transport: KrotTransportSession? = null

    @Volatile
    private var tunInterface: ParcelFileDescriptor? = null

    @Volatile
    private var tunInput: FileInputStream? = null

    @Volatile
    private var tunOutput: FileOutputStream? = null

    fun start() {
        check(stopped.compareAndSet(true, false)) { "KRot session is already running" }

        val rawSocket = Socket()
        rawSocket.bind(null)
        if (!protectSocket(rawSocket)) {
            log("KRot socket protect() returned false after bind; continuing with app-level VPN exclusion")
        }
        rawSocket.tcpNoDelay = true
        rawSocket.connect(InetSocketAddress(spec.server.host, spec.server.port), CONNECT_TIMEOUT_MS)
        log("KRot TCP cover channel established: ${spec.server.host}:${spec.server.port}")

        val openedTransport = KrotProtocol.open(rawSocket, spec, log)
        transport = openedTransport

        val tun = establishTunInterface()
        tunInterface = tun
        tunInput = FileInputStream(tun.fileDescriptor)
        tunOutput = FileOutputStream(tun.fileDescriptor)
        startRelayLoops(openedTransport)
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return

        runCatching {
            transport?.secureStream?.writeFrame(KrotFrameType.Close, ByteArray(0))
        }
        log(
            "KRot data-plane counters: tun->relay=${tunToRelayPackets.get()}/${tunToRelayBytes.get()}b " +
                "relay->tun=${relayToTunPackets.get()}/${relayToTunBytes.get()}b"
        )
        runCatching { tunInput?.close() }
        runCatching { tunOutput?.close() }
        runCatching { tunInterface?.close() }
        runCatching { transport?.close() }
        tunInput = null
        tunOutput = null
        tunInterface = null
        transport = null
        scope.cancel()
    }

    private fun startRelayLoops(openedTransport: KrotTransportSession) {
        val input = checkNotNull(tunInput)
        val output = checkNotNull(tunOutput)
        val secure = openedTransport.secureStream

        scope.launch {
            val buffer = ByteArray(MAX_PACKET_SIZE)
            try {
                while (isActive && !stopped.get()) {
                    val len = input.read(buffer)
                    if (len <= 0) break
                    val packet = buffer.copyOf(len)
                    if (!KrotPacketTrace.isRoutableIpv4From(packet, spec.tunnel.clientIp)) {
                        val count = tunToRelayPackets.get() + 1
                        if (KrotPacketTrace.shouldLog(count)) {
                            log("KRot drop tun->relay ${packet.size}b ${KrotPacketTrace.describe(packet)}")
                        }
                        continue
                    }
                    val count = tunToRelayPackets.incrementAndGet()
                    tunToRelayBytes.addAndGet(packet.size.toLong())
                    if (KrotPacketTrace.shouldLog(count)) {
                        log("KRot tun->relay #$count ${packet.size}b ${KrotPacketTrace.describe(packet)}")
                    }
                    secure.writeFrame(KrotFrameType.Packet, packet)
                }
            } catch (error: Throwable) {
                reportFailure(error)
            }
        }

        scope.launch {
            try {
                while (isActive && !stopped.get()) {
                    val frame = secure.readFrame()
                    when (frame.type) {
                        KrotFrameType.Packet -> {
                            val packet = frame.payload
                            if (!KrotPacketTrace.isIpv4To(packet, spec.tunnel.clientIp)) {
                                val count = relayToTunPackets.get() + 1
                                if (KrotPacketTrace.shouldLog(count)) {
                                    log("KRot drop relay->tun ${packet.size}b ${KrotPacketTrace.describe(packet)}")
                                }
                                continue
                            }
                            val count = relayToTunPackets.incrementAndGet()
                            relayToTunBytes.addAndGet(packet.size.toLong())
                            if (KrotPacketTrace.shouldLog(count)) {
                                log("KRot relay->tun #$count ${packet.size}b ${KrotPacketTrace.describe(packet)}")
                            }
                            output.write(packet)
                            output.flush()
                        }

                        KrotFrameType.Ping -> secure.writeFrame(KrotFrameType.Pong, ByteArray(0))
                        KrotFrameType.Pong -> Unit
                        KrotFrameType.Close -> {
                            reportFailure(IllegalStateException("KRot server closed the relay session"))
                            break
                        }
                    }
                }
            } catch (error: Throwable) {
                reportFailure(error)
            }
        }
    }

    private fun reportFailure(error: Throwable) {
        if (stopped.get()) return
        if (failureReported.compareAndSet(false, true)) {
            onFailure(error)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS: Int = 12_000
        const val MAX_PACKET_SIZE: Int = 65_535
    }
}

private object KrotPacketTrace {
    fun shouldLog(count: Long): Boolean =
        count <= 10 || count == 25L || count == 50L || count == 100L || count % 250L == 0L

    fun isRoutableIpv4From(packet: ByteArray, source: String): Boolean =
        isIpv4From(packet, source) && isRoutableDestination(packet)

    fun isIpv4To(packet: ByteArray, destination: String): Boolean =
        isIpv4AddressMatch(packet, destination, offset = 16)

    fun describe(packet: ByteArray): String {
        if (packet.isEmpty()) return "empty"
        val version = (packet[0].toInt() ushr 4) and 0x0f
        if (version != 4) return "ip_version=$version"
        if (packet.size < 20) return "ipv4 truncated"
        val src = addressAt(packet, 12)
        val dst = addressAt(packet, 16)
        val protocol = when (packet[9].toInt() and 0xff) {
            1 -> "ICMP"
            6 -> "TCP"
            17 -> "UDP"
            else -> "proto-${packet[9].toInt() and 0xff}"
        }
        return "IPv4 $protocol $src->$dst len=${packet.size}"
    }

    private fun isIpv4From(packet: ByteArray, source: String): Boolean =
        isIpv4AddressMatch(packet, source, offset = 12)

    private fun isIpv4AddressMatch(packet: ByteArray, expected: String, offset: Int): Boolean {
        if (packet.size < offset + 4 || ((packet[0].toInt() ushr 4) and 0x0f) != 4) return false
        val expectedBytes = runCatching { InetAddress.getByName(expected).address }.getOrNull() ?: return false
        if (expectedBytes.size != 4) return false
        for (index in 0 until 4) {
            if (packet[offset + index] != expectedBytes[index]) return false
        }
        return true
    }

    private fun isRoutableDestination(packet: ByteArray): Boolean {
        if (packet.size < 20 || ((packet[0].toInt() ushr 4) and 0x0f) != 4) return false
        val a = packet[16].toInt() and 0xff
        val b = packet[17].toInt() and 0xff
        val c = packet[18].toInt() and 0xff
        val d = packet[19].toInt() and 0xff
        if (a >= 224) return false
        if (a == 255 && b == 255 && c == 255 && d == 255) return false
        if (a == 10 && b == 66 && c == 0 && d == 255) return false
        return true
    }

    private fun addressAt(packet: ByteArray, offset: Int): String {
        if (packet.size < offset + 4) return "?.?.?.?"
        return listOf(
            packet[offset].toInt() and 0xff,
            packet[offset + 1].toInt() and 0xff,
            packet[offset + 2].toInt() and 0xff,
            packet[offset + 3].toInt() and 0xff
        ).joinToString(".")
    }
}
