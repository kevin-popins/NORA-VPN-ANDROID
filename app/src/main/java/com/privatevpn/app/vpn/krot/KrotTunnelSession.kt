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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
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
    private val tunReadPackets = AtomicLong(0)
    private val tunReadBytes = AtomicLong(0)
    private val krotUplinkPackets = AtomicLong(0)
    private val krotUplinkBytes = AtomicLong(0)
    private val krotDownlinkPackets = AtomicLong(0)
    private val krotDownlinkBytes = AtomicLong(0)
    private val tunWritePackets = AtomicLong(0)
    private val tunWriteBytes = AtomicLong(0)
    private val droppedIpv6Packets = AtomicLong(0)

    @Volatile
    private var lastError: String? = null

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
            lastError = "KRot socket protect() returned false before connect"
            log("KRot socket protect() returned false before connect; continuing with app-level VPN exclusion")
        } else {
            log("KRot socket protected before connect")
        }
        rawSocket.tcpNoDelay = true
        rawSocket.connect(InetSocketAddress(spec.server.host, spec.server.port), CONNECT_TIMEOUT_MS)
        log("KRot TCP cover channel established: ${spec.server.host}:${spec.server.port}")

        val openedTransport = KrotProtocol.open(rawSocket, spec, log)
        transport = openedTransport
        log("KRot session established")

        val tun = establishTunInterface()
        tunInterface = tun
        tunInput = FileInputStream(tun.fileDescriptor)
        tunOutput = FileOutputStream(tun.fileDescriptor)
        startRelayLoops(openedTransport)
        startCounterLogger()
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return

        runCatching {
            transport?.secureStream?.writeFrame(KrotFrameType.Close, ByteArray(0))
        }
        logCounters("KRot data-plane counters")
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
                    if (len < 0) break
                    if (len == 0) continue
                    val packet = buffer.copyOf(len)
                    val readCount = tunReadPackets.incrementAndGet()
                    tunReadBytes.addAndGet(packet.size.toLong())
                    if (readCount == 1L) {
                        log("KRot first packet read from TUN: ${packet.size}b ${KrotPacketTrace.describe(packet)}")
                    }

                    when (KrotPacketTrace.version(packet)) {
                        4 -> Unit
                        6 -> {
                            val count = droppedIpv6Packets.incrementAndGet()
                            if (KrotPacketTrace.shouldLog(count)) {
                                log("KRot drop IPv6 tun->krot #$count ${packet.size}b ${KrotPacketTrace.describe(packet)}")
                            }
                            continue
                        }

                        else -> {
                            lastError = "Dropped non-IP TUN packet"
                            log("KRot drop non-IP tun->krot ${packet.size}b ${KrotPacketTrace.describe(packet)}")
                            continue
                        }
                    }

                    if (!KrotPacketTrace.isRoutableDestination(packet)) {
                        if (KrotPacketTrace.shouldLog(readCount)) {
                            log("KRot drop non-routable IPv4 tun->krot ${packet.size}b ${KrotPacketTrace.describe(packet)}")
                        }
                        continue
                    }

                    KrotPacketTrace.repairIpv4Checksums(packet)
                    val count = krotUplinkPackets.incrementAndGet()
                    krotUplinkBytes.addAndGet(packet.size.toLong())
                    if (count == 1L) {
                        log("KRot first packet sent to KRot: ${packet.size}b ${KrotPacketTrace.describe(packet)}")
                    }
                    if (KrotPacketTrace.shouldLog(count)) {
                        log("KRot tun->krot #$count ${packet.size}b ${KrotPacketTrace.describe(packet)}")
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
                            val downlinkCount = krotDownlinkPackets.incrementAndGet()
                            krotDownlinkBytes.addAndGet(packet.size.toLong())
                            if (downlinkCount == 1L) {
                                log("KRot first packet received from KRot: ${packet.size}b ${KrotPacketTrace.describe(packet)}")
                            }
                            if (KrotPacketTrace.shouldLog(downlinkCount)) {
                                log("KRot krot->tun #$downlinkCount ${packet.size}b ${KrotPacketTrace.describe(packet)}")
                            }
                            KrotPacketTrace.repairIpv4Checksums(packet)
                            output.write(packet)
                            output.flush()
                            val tunWriteCount = tunWritePackets.incrementAndGet()
                            tunWriteBytes.addAndGet(packet.size.toLong())
                            if (tunWriteCount == 1L) {
                                log("KRot first packet written to TUN: ${packet.size}b ${KrotPacketTrace.describe(packet)}")
                            }
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

    private fun startCounterLogger() {
        scope.launch {
            delay(DATA_PLANE_IDLE_LOG_MS)
            if (!stopped.get() && tunReadPackets.get() == 0L) {
                lastError = "No packets read from Android TUN yet"
                logCounters("KRot data-plane idle")
            }
            while (isActive && !stopped.get()) {
                delay(COUNTER_LOG_INTERVAL_MS)
                logCounters("KRot data-plane counters")
            }
        }
    }

    private fun logCounters(prefix: String) {
        log(
            "$prefix: " +
                "tun_read_packets=${tunReadPackets.get()} " +
                "tun_read_bytes=${tunReadBytes.get()} " +
                "krot_uplink_packets=${krotUplinkPackets.get()} " +
                "krot_uplink_bytes=${krotUplinkBytes.get()} " +
                "krot_downlink_packets=${krotDownlinkPackets.get()} " +
                "krot_downlink_bytes=${krotDownlinkBytes.get()} " +
                "tun_write_packets=${tunWritePackets.get()} " +
                "tun_write_bytes=${tunWriteBytes.get()} " +
                "dropped_ipv6_packets=${droppedIpv6Packets.get()} " +
                "last_error=${lastError ?: "none"}"
        )
    }

    private fun reportFailure(error: Throwable) {
        if (stopped.get()) return
        lastError = error.message ?: error::class.java.simpleName
        if (failureReported.compareAndSet(false, true)) {
            onFailure(error)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS: Int = 12_000
        const val MAX_PACKET_SIZE: Int = 65_535
        const val DATA_PLANE_IDLE_LOG_MS: Long = 5_000
        const val COUNTER_LOG_INTERVAL_MS: Long = 10_000
    }
}

private object KrotPacketTrace {
    fun shouldLog(count: Long): Boolean =
        count <= 10 || count == 25L || count == 50L || count == 100L || count % 250L == 0L

    fun version(packet: ByteArray): Int =
        if (packet.isEmpty()) -1 else (packet[0].toInt() ushr 4) and 0x0f

    fun describe(packet: ByteArray): String {
        if (packet.isEmpty()) return "empty"
        val version = version(packet)
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

    fun isRoutableDestination(packet: ByteArray): Boolean {
        if (packet.size < 20 || version(packet) != 4) return false
        val a = packet[16].toInt() and 0xff
        val b = packet[17].toInt() and 0xff
        val c = packet[18].toInt() and 0xff
        val d = packet[19].toInt() and 0xff
        if (a >= 224) return false
        if (a == 255 && b == 255 && c == 255 && d == 255) return false
        if (a == 10 && b == 66 && c == 0 && d == 255) return false
        return true
    }

    fun repairIpv4Checksums(packet: ByteArray) {
        if (version(packet) != 4 || packet.size < 20) return

        val headerLen = (packet[0].toInt() and 0x0f) * 4
        if (headerLen < 20 || packet.size < headerLen) return

        val totalLen = readU16(packet, 2)
        if (totalLen < headerLen || totalLen > packet.size) return

        val fragment = readU16(packet, 6)
        fixIpv4HeaderChecksum(packet, headerLen)
        if ((fragment and 0x3fff) != 0) return

        val transportLen = totalLen - headerLen
        if (transportLen <= 0) return

        when (packet[9].toInt() and 0xff) {
            1 -> {
                if (transportLen >= 4) {
                    packet[headerLen + 2] = 0
                    packet[headerLen + 3] = 0
                    writeChecksum(packet, headerLen + 2, computeChecksum(packet, headerLen, transportLen))
                }
            }

            6 -> {
                if (transportLen >= 20) {
                    packet[headerLen + 16] = 0
                    packet[headerLen + 17] = 0
                    writeChecksum(packet, headerLen + 16, computeTcpUdpChecksum(packet, headerLen, transportLen))
                }
            }

            17 -> {
                if (transportLen >= 8) {
                    packet[headerLen + 6] = 0
                    packet[headerLen + 7] = 0
                    var checksum = computeTcpUdpChecksum(packet, headerLen, transportLen)
                    if (checksum == 0) checksum = 0xffff
                    writeChecksum(packet, headerLen + 6, checksum)
                }
            }
        }
    }

    private fun fixIpv4HeaderChecksum(packet: ByteArray, headerLen: Int) {
        packet[10] = 0
        packet[11] = 0
        writeChecksum(packet, 10, computeChecksum(packet, 0, headerLen))
    }

    private fun computeTcpUdpChecksum(packet: ByteArray, headerLen: Int, transportLen: Int): Int {
        var sum = 0
        sum = addWord(sum, packet, 12)
        sum = addWord(sum, packet, 14)
        sum = addWord(sum, packet, 16)
        sum = addWord(sum, packet, 18)
        sum += packet[9].toInt() and 0xff
        sum += transportLen
        sum = addSpan(sum, packet, headerLen, transportLen)
        return foldChecksum(sum)
    }

    private fun computeChecksum(packet: ByteArray, offset: Int, len: Int): Int =
        foldChecksum(addSpan(0, packet, offset, len))

    private fun addSpan(initial: Int, packet: ByteArray, offset: Int, len: Int): Int {
        var sum = initial
        var index = offset
        val end = offset + len
        while (index + 1 < end) {
            sum += readU16(packet, index)
            index += 2
        }
        if (index < end) {
            sum += (packet[index].toInt() and 0xff) shl 8
        }
        return sum
    }

    private fun addWord(sum: Int, packet: ByteArray, offset: Int): Int =
        sum + readU16(packet, offset)

    private fun foldChecksum(value: Int): Int {
        var sum = value
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xffff) + (sum ushr 16)
        }
        return sum.inv() and 0xffff
    }

    private fun writeChecksum(packet: ByteArray, offset: Int, checksum: Int) {
        packet[offset] = ((checksum ushr 8) and 0xff).toByte()
        packet[offset + 1] = (checksum and 0xff).toByte()
    }

    private fun readU16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)

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
