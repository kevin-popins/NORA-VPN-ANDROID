package com.privatevpn.app.vpn.dataplane

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

class SplitTunnelPacketPolicyTest {
    private var owner = TRUSTED_UID
    private var lookups = 0
    private var now = 1_000L
    private var seen: Triple<Int, InetSocketAddress, InetSocketAddress>? = null
    private val dns = address("192.0.2.53")
    private fun policy() = SplitTunnelPacketPolicy(
        ownerLookup = { protocol, local, remote ->
            lookups++
            seen = Triple(protocol, local, remote)
            owner
        },
        uidAllowed = { it == TRUSTED_UID },
        configuredDns = setOf(dns),
        clock = { now }
    )

    @Test fun trustedTcpUsesExactOriginalTuple() {
        val packet = ipv4(TCP, flags = SYN)
        assertTrue(policy().allowsPacket(packet, packet.size))
        assertEquals(TCP, seen!!.first)
        assertEquals(InetSocketAddress(address("10.0.0.2"), 32_000), seen!!.second)
        assertEquals(InetSocketAddress(address("203.0.113.20"), 443), seen!!.third)
    }

    @Test fun excludedAndUnknownOwnersAreDenied() {
        val packet = ipv4(TCP, flags = SYN)
        val policy = policy()
        owner = TRUSTED_UID + 1
        assertFalse(policy.allowsPacket(packet, packet.size))
        owner = -1
        assertFalse(policy.allowsPacket(packet, packet.size))
    }

    @Test fun unknownCannotBeWhitelistedByPermissivePredicateOrDnsPort() {
        val packet = ipv4(UDP, destination = dns, destinationPort = 53)
        val policy = SplitTunnelPacketPolicy({ _, _, _ -> -1 }, { true }, setOf(dns))
        assertFalse(policy.allowsPacket(packet, packet.size))
    }

    @Test fun ownershipLookupFailureIsDenied() {
        val packet = ipv4(UDP)
        val policy = SplitTunnelPacketPolicy({ _, _, _ -> throw SecurityException("synthetic") }, { true }, emptySet())
        assertFalse(policy.allowsPacket(packet, packet.size))
    }

    @Test fun establishedTcpSurvivesClosedSocketButSynMustRevalidate() {
        val policy = policy()
        val syn = ipv4(TCP, flags = SYN)
        val ack = ipv4(TCP, flags = ACK)
        assertTrue(policy.allowsPacket(syn, syn.size))
        owner = -1
        assertTrue(policy.allowsPacket(ack, ack.size))
        assertEquals(1, lookups)
        assertFalse(policy.allowsPacket(syn, syn.size))
        assertFalse(policy.allowsPacket(ack, ack.size))
        assertEquals(3, lookups)
    }

    @Test fun tcpFinAndResetReleaseAdmission() {
        for (closingFlag in listOf(0x01, 0x04)) {
            owner = TRUSTED_UID
            val policy = policy()
            val syn = ipv4(TCP, flags = SYN)
            val closing = ipv4(TCP, flags = closingFlag or ACK)
            val ack = ipv4(TCP, flags = ACK)
            assertTrue(policy.allowsPacket(syn, syn.size))
            assertTrue(policy.allowsPacket(closing, closing.size))
            owner = -1
            assertFalse(policy.allowsPacket(ack, ack.size))
        }
    }

    @Test fun tcpAdmissionExpiresAndCacheIsBounded() {
        val policy = policy()
        val syn = ipv4(TCP, flags = SYN)
        val ack = ipv4(TCP, flags = ACK)
        assertTrue(policy.allowsPacket(syn, syn.size))
        now += 120_000
        owner = -1
        assertFalse(policy.allowsPacket(ack, ack.size))
        owner = TRUSTED_UID
        for (port in 20_000..24_096) {
            val packet = ipv4(TCP, flags = SYN, sourcePort = port)
            assertTrue(policy.allowsPacket(packet, packet.size))
        }
        owner = -1
        val oldest = ipv4(TCP, flags = ACK, sourcePort = 20_000)
        assertFalse(policy.allowsPacket(oldest, oldest.size))
    }

    @Test fun udpTupleReuseNeverInheritsTrustedAdmission() {
        val policy = policy()
        val packet = ipv4(UDP)
        assertTrue(policy.allowsPacket(packet, packet.size))
        owner = TRUSTED_UID + 1
        assertFalse(policy.allowsPacket(packet, packet.size))
        owner = -1
        assertFalse(policy.allowsPacket(packet, packet.size))
        assertEquals(3, lookups)
    }

    @Test fun systemDnsRequiresKnownExactUidDestinationAndPort() {
        val policy = policy()
        for (systemUid in listOf(0, 1051)) {
            owner = systemUid
            val packet = ipv4(UDP, destination = dns, destinationPort = 53)
            assertTrue(policy.allowsPacket(packet, packet.size))
            val wrongDestination = ipv4(UDP, destinationPort = 53)
            assertFalse(policy.allowsPacket(wrongDestination, wrongDestination.size))
            val wrongPort = ipv4(UDP, destination = dns, destinationPort = 443)
            assertFalse(policy.allowsPacket(wrongPort, wrongPort.size))
        }
        for (otherUid in listOf(-1, 1000, 1001, 1052, TRUSTED_UID + 1)) {
            owner = otherUid
            val packet = ipv4(UDP, destination = dns, destinationPort = 53)
            assertFalse(policy.allowsPacket(packet, packet.size))
        }
    }

    @Test fun ipOptionsKeepCorrectTransportOffsets() {
        val policy = policy()
        val options = ipv4(UDP, ipHeaderLength = 24)
        assertTrue(policy.allowsPacket(options, options.size))
        val ipv6 = ipv6Udp(destinationOptions = true)
        assertTrue(policy.allowsPacket(ipv6, ipv6.size))
        assertEquals(InetSocketAddress(address("2001:db8::2"), 32_000), seen!!.second)
        assertEquals(InetSocketAddress(address("2001:db8::20"), 443), seen!!.third)
    }

    @Test fun fragmentsAndUnsupportedProtocolsFailBeforeOwnerLookup() {
        val policy = policy()
        for (fragmentFlags in listOf(0x2000, 0x0001, 0x8000)) {
            val packet = ipv4(UDP)
            put16(packet, 6, fragmentFlags)
            assertFalse(policy.allowsPacket(packet, packet.size))
        }
        for (protocol in listOf(1, 47, 50, 58)) {
            val packet = ipv4(protocol)
            assertFalse(policy.allowsPacket(packet, packet.size))
        }
        val ipv6Fragment = ipv6Udp().also { it[6] = 44 }
        assertFalse(policy.allowsPacket(ipv6Fragment, ipv6Fragment.size))
        assertEquals(0, lookups)
    }

    @Test fun malformedPacketsFailBeforeOwnerLookup() {
        val policy = policy()
        val malformed = listOf(
            byteArrayOf(), byteArrayOf(0x45),
            ipv4(TCP).also { it[0] = 0x44 },
            ipv4(TCP).also { put16(it, 2, it.size + 1) },
            ipv4(TCP).also { it[32] = 0x40 },
            ipv4(TCP).also { it[32] = 0xF0.toByte() },
            ipv4(UDP).also { put16(it, 24, 7) },
            ipv6Udp().also { put16(it, 4, 0) },
            ipv6Udp(destinationOptions = true).also { it[41] = 0x7F }
        )
        malformed.forEach { assertFalse(policy.allowsPacket(it, it.size)) }
        val valid = ipv4(UDP)
        assertFalse(policy.allowsPacket(valid, valid.size + 1))
        assertFalse(policy.allowsPacket(valid, -1))
        assertEquals(0, lookups)
    }

    private fun ipv4(
        protocol: Int, flags: Int = ACK, sourcePort: Int = 32_000,
        destinationPort: Int = 443, destination: InetAddress = address("203.0.113.20"),
        ipHeaderLength: Int = 20
    ): ByteArray {
        val transportLength = if (protocol == TCP) 20 else 8
        val packet = ByteArray(ipHeaderLength + transportLength)
        packet[0] = (0x40 or (ipHeaderLength / 4)).toByte()
        put16(packet, 2, packet.size)
        packet[8] = 64
        packet[9] = protocol.toByte()
        address("10.0.0.2").address.copyInto(packet, 12)
        destination.address.copyInto(packet, 16)
        put16(packet, ipHeaderLength, sourcePort)
        put16(packet, ipHeaderLength + 2, destinationPort)
        if (protocol == TCP) {
            packet[ipHeaderLength + 12] = 0x50
            packet[ipHeaderLength + 13] = flags.toByte()
        } else put16(packet, ipHeaderLength + 4, transportLength)
        return packet
    }

    private fun ipv6Udp(destinationOptions: Boolean = false): ByteArray {
        val extensionLength = if (destinationOptions) 8 else 0
        val packet = ByteArray(48 + extensionLength)
        packet[0] = 0x60
        put16(packet, 4, 8 + extensionLength)
        packet[6] = (if (destinationOptions) 60 else UDP).toByte()
        address("2001:db8::2").address.copyInto(packet, 8)
        address("2001:db8::20").address.copyInto(packet, 24)
        if (destinationOptions) packet[40] = UDP.toByte()
        put16(packet, 40 + extensionLength, 32_000)
        put16(packet, 42 + extensionLength, 443)
        put16(packet, 44 + extensionLength, 8)
        return packet
    }

    private fun put16(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 8).toByte()
        packet[offset + 1] = value.toByte()
    }
    private fun address(value: String): InetAddress = InetAddress.getByName(value)
    private companion object {
        const val TRUSTED_UID = 10_123
        const val TCP = 6
        const val UDP = 17
        const val SYN = 2
        const val ACK = 16
    }
}
