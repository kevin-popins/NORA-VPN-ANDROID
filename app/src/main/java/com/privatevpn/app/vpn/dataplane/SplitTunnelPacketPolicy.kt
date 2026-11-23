package com.privatevpn.app.vpn.dataplane

import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * A second boundary behind Android's per-app routing. Unknown owners are denied: Android also
 * reports an excluded socket's owner as unknown to the active VPN app.
 *
 * UDP ownership is deliberately not cached. A different app can reuse a closed UDP socket's
 * local port and destination. TCP SYNs likewise always replace any cached admission decision.
 */
class SplitTunnelPacketPolicy(
    private val ownerLookup: (Int, InetSocketAddress, InetSocketAddress) -> Int,
    private val uidAllowed: (Int) -> Boolean,
    configuredDns: Set<InetAddress>,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : TunPacketPolicy {
    private val configuredDns = configuredDns.toSet()
    private data class Flow(
        val protocol: Int,
        val local: InetSocketAddress,
        val remote: InetSocketAddress
    )

    private data class PacketFlow(val flow: Flow, val tcpFlags: Int)

    private val tcpAdmissions = object : LinkedHashMap<Flow, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Flow, Long>?): Boolean =
            size > MAX_TCP_FLOWS
    }

    @Synchronized
    override fun allowsPacket(packet: ByteArray, length: Int): Boolean {
        val parsed = parse(packet, length) ?: return false
        val flow = parsed.flow
        val now = clock()
        val syn = flow.protocol == TCP && parsed.tcpFlags and SYN != 0
        if (syn) tcpAdmissions.remove(flow)

        if (flow.protocol == TCP && !syn) {
            val lastSeen = tcpAdmissions[flow]
            if (lastSeen != null && now >= lastSeen && now - lastSeen < TCP_IDLE_MS) {
                rememberTcp(parsed, now)
                return true
            }
            tcpAdmissions.remove(flow)
        }

        val owner = try {
            ownerLookup(flow.protocol, flow.local, flow.remote)
        } catch (_: Exception) {
            return false
        }
        if (owner < 0) return false
        val allowed = try {
            uidAllowed(owner) || (
                owner in SYSTEM_DNS_UIDS && flow.remote.port == DNS_PORT &&
                    flow.remote.address in configuredDns
                )
        } catch (_: Exception) {
            false
        }
        if (!allowed) return false
        if (flow.protocol == TCP) rememberTcp(parsed, now)
        return true
    }

    private fun rememberTcp(parsed: PacketFlow, now: Long) {
        if (parsed.tcpFlags and (FIN or RST) != 0) tcpAdmissions.remove(parsed.flow)
        else tcpAdmissions[parsed.flow] = now
    }

    private fun parse(packet: ByteArray, length: Int): PacketFlow? {
        if (length < 1 || length > packet.size) return null
        val transportOffset: Int
        val protocol: Int
        val source: InetAddress
        val destination: InetAddress
        when (unsigned(packet[0]) ushr 4) {
            4 -> {
                if (length < 20) return null
                val headerLength = (unsigned(packet[0]) and 15) * 4
                if (headerLength < 20 || headerLength > length || u16(packet, 2) != length) return null
                // Neither first fragments nor later fragments have a complete attributable flow.
                if (u16(packet, 6) and 0xBFFF != 0) return null
                protocol = unsigned(packet[9])
                transportOffset = headerLength
                source = InetAddress.getByAddress(packet.copyOfRange(12, 16))
                destination = InetAddress.getByAddress(packet.copyOfRange(16, 20))
            }
            6 -> {
                if (length < 40 || u16(packet, 4) == 0 || 40 + u16(packet, 4) != length) return null
                source = InetAddress.getByAddress(packet.copyOfRange(8, 24))
                destination = InetAddress.getByAddress(packet.copyOfRange(24, 40))
                var next = unsigned(packet[6])
                var offset = 40
                var extensionCount = 0
                // Hop-by-hop and destination options do not change the socket's endpoint tuple.
                while (next == 0 || next == 60) {
                    if (++extensionCount > MAX_IPV6_EXTENSIONS || offset + 2 > length) return null
                    val nextOffset = offset + (unsigned(packet[offset + 1]) + 1) * 8
                    if (nextOffset > length) return null
                    next = unsigned(packet[offset])
                    offset = nextOffset
                }
                // Fragment, routing, ESP/AH and other unsupported extension chains fail closed.
                protocol = next
                transportOffset = offset
            }
            else -> return null
        }

        if (protocol != TCP && protocol != UDP) return null
        val transportLength = length - transportOffset
        val flags: Int
        when (protocol) {
            TCP -> {
                if (transportLength < 20) return null
                val headerLength = (unsigned(packet[transportOffset + 12]) ushr 4) * 4
                if (headerLength < 20 || headerLength > transportLength) return null
                flags = unsigned(packet[transportOffset + 13])
            }
            else -> {
                if (transportLength < 8 || u16(packet, transportOffset + 4) != transportLength) return null
                flags = 0
            }
        }
        val sourcePort = u16(packet, transportOffset)
        val destinationPort = u16(packet, transportOffset + 2)
        if (sourcePort == 0 || destinationPort == 0) return null
        return PacketFlow(
            Flow(protocol, InetSocketAddress(source, sourcePort), InetSocketAddress(destination, destinationPort)),
            flags
        )
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (unsigned(bytes[offset]) shl 8) or unsigned(bytes[offset + 1])

    private fun unsigned(byte: Byte): Int = byte.toInt() and 255

    private companion object {
        const val TCP = 6
        const val UDP = 17
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val DNS_PORT = 53
        const val MAX_TCP_FLOWS = 4096
        const val TCP_IDLE_MS = 120_000L
        const val MAX_IPV6_EXTENSIONS = 8
        val SYSTEM_DNS_UIDS = setOf(0, 1051)
    }
}
