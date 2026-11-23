package com.privatevpn.app.vpn.dataplane

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Packet-preserving bridge. The real TUN is borrowed; this class owns only the socketpair.
 * The caller must stop and join the native consumer before closing this relay's native FD.
 */
class GuardedTunRelay(
    private val realTun: FileDescriptor,
    private val packetPolicy: TunPacketPolicy,
    private val onFailure: (Throwable) -> Unit
) : AutoCloseable {
    data class Counters(val admittedPackets: Long, val deniedPackets: Long)

    private val endpoints = createEndpoints()
    private val relayEndpoint = endpoints.first
    private val nativeEndpoint = endpoints.second
    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val failureReported = AtomicBoolean(false)
    private val admittedPackets = AtomicLong()
    private val deniedPackets = AtomicLong()
    private var uplink: Thread? = null
    private var downlink: Thread? = null

    val nativeFd: Int get() = nativeEndpoint.fd

    fun counters(): Counters = Counters(admittedPackets.get(), deniedPackets.get())

    fun start() {
        check(!closed.get() && started.compareAndSet(false, true)) { "Packet relay cannot be restarted" }
        uplink = worker("privatevpn-guard-uplink", realTun, relayEndpoint.fileDescriptor, true)
        downlink = worker("privatevpn-guard-downlink", relayEndpoint.fileDescriptor, realTun, false)
        try {
            uplink!!.start()
            downlink!!.start()
        } catch (error: Throwable) {
            stopAdmission()
            close()
            throw error
        }
    }

    fun isRunning(): Boolean = started.get() && !stopped.get() &&
        uplink?.isAlive == true && downlink?.isAlive == true

    fun stopAdmission() {
        stopped.set(true)
    }

    override fun close() {
        if (closed.get()) return
        stopAdmission()
        listOfNotNull(uplink, downlink).forEach { worker ->
            if (worker !== Thread.currentThread()) worker.join(STOP_TIMEOUT_MS)
        }
        // Keep descriptors referenced if a worker has not exited; never close a reused FD.
        if (uplink?.isAlive == true || downlink?.isAlive == true) {
            throw IOException("Packet relay did not stop")
        }
        if (closed.compareAndSet(false, true)) {
            try {
                relayEndpoint.close()
            } finally {
                nativeEndpoint.close()
            }
        }
    }

    private fun worker(name: String, source: FileDescriptor, sink: FileDescriptor, guarded: Boolean): Thread =
        Thread({
            val buffer = ByteArray(MAX_PACKET_SIZE)
            try {
                while (!stopped.get()) {
                    if (!awaitReady(source, OsConstants.POLLIN)) break
                    val length = try {
                        Os.read(source, buffer, 0, buffer.size)
                    } catch (error: ErrnoException) {
                        if (retryable(error)) continue
                        throw error
                    }
                    if (length <= 0) throw IOException("Packet relay source closed")
                    if (guarded) {
                        val admitted = try {
                            packetPolicy.allowsPacket(buffer, length)
                        } catch (error: Throwable) {
                            deniedPackets.incrementAndGet()
                            throw error
                        }
                        if (!admitted) {
                            deniedPackets.incrementAndGet()
                            continue
                        }
                        admittedPackets.incrementAndGet()
                    }
                    while (!stopped.get()) {
                        if (!awaitReady(sink, OsConstants.POLLOUT)) break
                        val written = try {
                            Os.write(sink, buffer, 0, length)
                        } catch (error: ErrnoException) {
                            if (retryable(error)) continue
                            throw error
                        }
                        if (written != length) throw IOException("Packet relay short packet write")
                        break
                    }
                }
            } catch (error: Throwable) {
                if (!stopped.get()) reportFailure(error)
            }
        }, name).apply { isDaemon = true }

    private fun awaitReady(fd: FileDescriptor, event: Int): Boolean {
        val poll = StructPollfd().apply {
            this.fd = fd
            events = event.toShort()
        }
        while (!stopped.get()) {
            val count = try {
                Os.poll(arrayOf(poll), POLL_TIMEOUT_MS)
            } catch (error: ErrnoException) {
                if (retryable(error)) continue
                throw error
            }
            if (stopped.get()) return false
            if (count == 0) continue
            val returned = poll.revents.toInt()
            if (returned and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) {
                throw IOException("Packet relay descriptor unavailable")
            }
            if (returned and event != 0) return true
        }
        return false
    }

    private fun reportFailure(error: Throwable) {
        stopped.set(true)
        if (!failureReported.compareAndSet(false, true)) return
        // A callback may stop the data plane. Invoke it outside the relay workers and locks.
        Thread({ runCatching { onFailure(error) } }, "privatevpn-guard-failure").apply {
            isDaemon = true
            start()
        }
    }

    private fun retryable(error: ErrnoException): Boolean =
        error.errno == OsConstants.EINTR || error.errno == OsConstants.EAGAIN

    private companion object {
        const val MAX_PACKET_SIZE = 65_575
        const val POLL_TIMEOUT_MS = 150
        const val STOP_TIMEOUT_MS = 1_000L

        fun createEndpoints(): Pair<ParcelFileDescriptor, ParcelFileDescriptor> {
            val first = FileDescriptor()
            val second = FileDescriptor()
            Os.socketpair(
                OsConstants.AF_UNIX,
                OsConstants.SOCK_DGRAM or OsConstants.SOCK_NONBLOCK or OsConstants.SOCK_CLOEXEC,
                0,
                first,
                second
            )
            var firstCopy: ParcelFileDescriptor? = null
            try {
                val copiedFirst = ParcelFileDescriptor.dup(first)
                firstCopy = copiedFirst
                val secondCopy = ParcelFileDescriptor.dup(second)
                return copiedFirst to secondCopy
            } catch (error: Throwable) {
                firstCopy?.close()
                throw error
            } finally {
                try {
                    Os.close(first)
                } finally {
                    Os.close(second)
                }
            }
        }
    }
}
