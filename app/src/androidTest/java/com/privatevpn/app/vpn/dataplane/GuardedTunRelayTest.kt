package com.privatevpn.app.vpn.dataplane

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.privatevpn.app.vpn.PrivateVpnService
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.io.FileDescriptor
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Synthetic packet FDs only. These tests never establish, replace, or connect a device VPN. */
class GuardedTunRelayTest {
    @Test fun relayPreservesBoundariesFiltersUplinkAndLeavesBorrowedTunOpen() {
        val fakeTun = pair()
        val failure = AtomicReference<Throwable?>()
        val relay = GuardedTunRelay(fakeTun.first.fileDescriptor, TunPacketPolicy { packet, length ->
            length > 0 && packet[0] == 0x45.toByte()
        }) { failure.set(it) }
        var native: ParcelFileDescriptor? = null
        try {
            native = ParcelFileDescriptor.fromFd(relay.nativeFd)
            relay.start()
            val first = byteArrayOf(0x45, 1, 2)
            val denied = byteArrayOf(0x60, 9)
            val second = byteArrayOf(0x45, 3, 4, 5, 6)
            send(fakeTun.second.fileDescriptor, first)
            send(fakeTun.second.fileDescriptor, denied)
            send(fakeTun.second.fileDescriptor, second)
            assertArrayEquals(first, receive(native.fileDescriptor))
            assertArrayEquals(second, receive(native.fileDescriptor))
            assertNull(receive(native.fileDescriptor, 250))
            send(native.fileDescriptor, denied)
            assertArrayEquals(denied, receive(fakeTun.second.fileDescriptor))
            assertTrue(relay.isRunning())
            assertEquals(GuardedTunRelay.Counters(2, 1), relay.counters())
            assertNull(failure.get())
        } finally {
            native?.close()
            relay.close()
            assertFalse(relay.isRunning())
            assertTrue("Real TUN descriptor remains caller-owned", fakeTun.first.fileDescriptor.valid())
            fakeTun.first.close()
            fakeTun.second.close()
        }
    }

    @Test fun policyFailureClosesAdmissionAndReportsOnce() {
        val fakeTun = pair()
        val notified = CountDownLatch(1)
        val failures = AtomicInteger()
        val relay = GuardedTunRelay(fakeTun.first.fileDescriptor, TunPacketPolicy { _, _ ->
            throw IllegalStateException("Synthetic policy failure")
        }) {
            failures.incrementAndGet()
            notified.countDown()
        }
        val native = ParcelFileDescriptor.fromFd(relay.nativeFd)
        try {
            relay.start()
            send(fakeTun.second.fileDescriptor, byteArrayOf(0x45, 1))
            assertTrue(notified.await(2, TimeUnit.SECONDS))
            assertNull(receive(native.fileDescriptor, 250))
            assertFalse(relay.isRunning())
            assertEquals(1, failures.get())
        } finally {
            native.close()
            relay.close()
            fakeTun.first.close()
            fakeTun.second.close()
        }
    }

    @Test fun idleRelayStopsPromptlyAcrossRepeatedSessions() {
        repeat(4) {
            val fakeTun = pair()
            val relay = GuardedTunRelay(fakeTun.first.fileDescriptor, TunPacketPolicy { _, _ -> true }) {
                throw AssertionError(it)
            }
            try {
                relay.start()
                assertTrue(relay.isRunning())
                val started = System.nanoTime()
                relay.close()
                assertTrue("Polling workers must stop without traffic", System.nanoTime() - started < 2_000_000_000L)
                relay.close()
            } finally {
                relay.close()
                fakeTun.first.close()
                fakeTun.second.close()
            }
        }
    }

    @Test fun bundledNativeAcceptsRelayFdAndKeepsVirtualDnsBehindPolicy() {
        // The native API has process-global cancellation. Never compete with a user's live runtime.
        assumeFalse("Leave the existing VPN runtime untouched", PrivateVpnService.isRunning())
        val fakeTun = pair()
        val owner = AtomicInteger(TRUSTED_UID)
        val failure = AtomicReference<Throwable?>()
        val policy = SplitTunnelPacketPolicy(
            ownerLookup = { _, _, _ -> owner.get() },
            uidAllowed = { it == TRUSTED_UID },
            configuredDns = setOf(InetAddress.getByName("192.0.2.53"))
        )
        val dataPlane = Tun2ProxyDataPlane { }
        try {
            // Virtual DNS answers locally. No SOCKS connection or external request is required.
            dataPlane.start("127.0.0.1", 9, "synthetic-user", "synthetic-password",
                fakeTun.first.fd, 1500, policy) { failure.set(it) }.getOrThrow()
            val query = dnsPacket(0x1234)
            send(fakeTun.second.fileDescriptor, query)
            val response = receive(fakeTun.second.fileDescriptor)
            assertNotNull("Bundled native code must answer through the datagram relay", response)
            assertEquals(17, response!![9].toInt() and 255)
            assertEquals(0x12, response[28].toInt() and 255)
            assertEquals(0x34, response[29].toInt() and 255)
            assertTrue("DNS response flag", response[30].toInt() and 0x80 != 0)

            owner.set(TRUSTED_UID + 1)
            send(fakeTun.second.fileDescriptor, dnsPacket(0x1235))
            assertNull("UDP tuple reuse must not inherit the former owner's admission",
                receive(fakeTun.second.fileDescriptor, 350))
            owner.set(-1)
            send(fakeTun.second.fileDescriptor, dnsPacket(0x1236))
            assertNull("Unknown DNS owner must be denied", receive(fakeTun.second.fileDescriptor, 350))
            owner.set(TRUSTED_UID)
            send(fakeTun.second.fileDescriptor, dnsPacket(0x1237))
            assertNotNull("Trusted UDP remains usable after rejected packets", receive(fakeTun.second.fileDescriptor))
            assertTrue(dataPlane.isRunning())
            assertEquals(GuardedTunRelay.Counters(2, 2), dataPlane.packetGuardCounters())
            assertNull(failure.get())
        } finally {
            dataPlane.stop()
            assertFalse(dataPlane.isRunning())
            fakeTun.first.close()
            fakeTun.second.close()
        }
    }

    @Test fun nativeOwnershipIsExclusiveAcrossManagerInstancesAndReusableAfterStop() {
        assumeFalse("Leave the existing VPN runtime untouched", PrivateVpnService.isRunning())
        val firstTun = pair()
        val secondTun = pair()
        val first = Tun2ProxyDataPlane { }
        val second = Tun2ProxyDataPlane { }
        val policy = TunPacketPolicy { _, _ -> true }
        try {
            first.start("127.0.0.1", 9, "synthetic-user", "synthetic-password",
                firstTun.first.fd, 1500, policy).getOrThrow()
            val rejected = second.start("127.0.0.1", 9, "synthetic-user", "synthetic-password",
                secondTun.first.fd, 1500, policy)
            assertTrue("A second manager cannot claim the process-global native API", rejected.isFailure)
            second.stop()
            assertTrue("Stopping the rejected manager must not cancel the owner", first.isRunning())
            send(firstTun.second.fileDescriptor, dnsPacket(0x2345))
            assertNotNull(receive(firstTun.second.fileDescriptor))

            first.stop()
            assertFalse(first.isRunning())
            second.start("127.0.0.1", 9, "synthetic-user", "synthetic-password",
                secondTun.first.fd, 1500, policy).getOrThrow()
            first.stop()
            assertTrue("A former owner's stop must not cancel the new owner", second.isRunning())
            send(secondTun.second.fileDescriptor, dnsPacket(0x2346))
            assertNotNull(receive(secondTun.second.fileDescriptor))
        } finally {
            first.stop()
            second.stop()
            firstTun.first.close()
            firstTun.second.close()
            secondTun.first.close()
            secondTun.second.close()
        }
    }

    private fun dnsPacket(id: Int): ByteArray {
        val name = byteArrayOf(9) + "synthetic".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(7) + "invalid".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val dns = ByteArray(12) + name + byteArrayOf(0, 1, 0, 1)
        put16(dns, 0, id)
        put16(dns, 2, 0x0100)
        put16(dns, 4, 1)
        val packet = ByteArray(28) + dns
        packet[0] = 0x45
        put16(packet, 2, packet.size)
        packet[8] = 64
        packet[9] = 17
        byteArrayOf(10, 0, 0, 2).copyInto(packet, 12)
        byteArrayOf(192.toByte(), 0, 2, 53).copyInto(packet, 16)
        put16(packet, 20, 32_000)
        put16(packet, 22, 53)
        put16(packet, 24, 8 + dns.size)
        var sum = 0
        for (offset in 0 until 20 step 2) sum += ((packet[offset].toInt() and 255) shl 8) or (packet[offset + 1].toInt() and 255)
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        put16(packet, 10, sum.inv() and 0xFFFF)
        return packet
    }

    private fun put16(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 8).toByte()
        packet[offset + 1] = value.toByte()
    }

    private fun send(fd: FileDescriptor, packet: ByteArray) {
        assertEquals(packet.size, Os.write(fd, packet, 0, packet.size))
    }

    private fun receive(fd: FileDescriptor, timeout: Int = 2_000): ByteArray? {
        val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
        if (Os.poll(arrayOf(poll), timeout) == 0) return null
        val buffer = ByteArray(65_575)
        return buffer.copyOf(Os.read(fd, buffer, 0, buffer.size))
    }

    private fun pair(): Pair<ParcelFileDescriptor, ParcelFileDescriptor> {
        val first = FileDescriptor()
        val second = FileDescriptor()
        Os.socketpair(OsConstants.AF_UNIX,
            OsConstants.SOCK_DGRAM or OsConstants.SOCK_NONBLOCK or OsConstants.SOCK_CLOEXEC,
            0, first, second)
        try {
            val copied = ParcelFileDescriptor.dup(first)
            try {
                return copied to ParcelFileDescriptor.dup(second)
            } catch (error: Throwable) {
                copied.close()
                throw error
            }
        } finally {
            Os.close(first)
            Os.close(second)
        }
    }

    private companion object { const val TRUSTED_UID = 10_123 }
}
