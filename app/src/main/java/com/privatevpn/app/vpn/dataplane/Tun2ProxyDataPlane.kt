package com.privatevpn.app.vpn.dataplane

import android.os.ParcelFileDescriptor
import com.github.shadowsocks.bg.Tun2proxy
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class Tun2ProxyDataPlane(
    private val log: (String) -> Unit
) {
    private val lock = Any()

    private class Session(
        val relay: GuardedTunRelay?,
        val borrowedTun: ParcelFileDescriptor,
        val onFailure: (Throwable) -> Unit
    ) {
        val stopping = AtomicBoolean(false)
        val failureReported = AtomicBoolean(false)
        @Volatile var worker: Thread? = null
        @Volatile var lastExitCode: Int? = null
    }

    private data class NativeOwner(val manager: Tun2ProxyDataPlane, val session: Session)

    @Volatile private var session: Session? = null

    fun start(
        socksHost: String,
        socksPort: Int,
        socksUsername: String,
        socksPassword: String,
        tunFd: Int,
        mtu: Int,
        packetPolicy: TunPacketPolicy? = null,
        onFailure: (Throwable) -> Unit = {}
    ): Result<Unit> {
        synchronized(GLOBAL_LOCK) {
            val owner = nativeOwner
            if (owner != null) {
                if (owner.session.worker?.isAlive == true) {
                    return Result.failure(IllegalStateException("tun2proxy уже запущен или ещё останавливается"))
                }
                // A stopped/destroyed service may never get another cleanup turn. A future
                // manager can reap its dead worker's resources before claiming the native API.
                synchronized(owner.manager.lock) {
                    runCatching { owner.manager.releaseSession(owner.session) }
                        .onFailure { return Result.failure(it) }
                    if (owner.manager.session === owner.session) owner.manager.session = null
                }
            }
            synchronized(lock) {
                val previous = session
                if (previous != null) {
                    if (previous.worker?.isAlive == true) {
                        return Result.failure(IllegalStateException("tun2proxy уже запущен или ещё останавливается"))
                    }
                    runCatching { releaseSession(previous) }.onFailure { return Result.failure(it) }
                    session = null
                }

                require(socksPort in 1..65535) { "Некорректный порт data plane SOCKS" }
                require(socksUsername.isNotBlank() && socksPassword.isNotBlank()) {
                    "Для data plane SOCKS обязательны логин и пароль"
                }
                require(tunFd >= 0 && mtu in 1..65535) { "Некорректные параметры TUN" }

                val proxyUrl = buildProxyUrl(
                    host = socksHost,
                    port = socksPort,
                    username = socksUsername,
                    password = socksPassword
                )

                var newSession: Session? = null
                return runCatching {
                    // Keep an independently owned descriptor until native code actually returns,
                    // including when the service is destroyed during a slow native shutdown.
                    // Guarded mode gives native only the socketpair, never the real TUN.
                    val borrowedTun = ParcelFileDescriptor.fromFd(tunFd)
                    val relay = try {
                        if (packetPolicy != null) {
                            GuardedTunRelay(borrowedTun.fileDescriptor, packetPolicy) { error ->
                                newSession?.let { reportFailure(it, error) }
                            }
                        } else null
                    } catch (error: Throwable) {
                        borrowedTun.close()
                        throw error
                    }
                    val active = Session(relay, borrowedTun, onFailure)
                    newSession = active
                    session = active
                    nativeOwner = NativeOwner(this, active)
                    val thread = Thread {
                        val code = runCatching {
                            Tun2proxy.run(
                                proxyUrl,
                                relay?.nativeFd ?: borrowedTun.fd,
                                false,
                                mtu.toChar(),
                                Tun2proxy.VERBOSITY_INFO,
                                Tun2proxy.DNS_VIRTUAL
                            )
                        }.getOrElse { error ->
                            reportFailure(active, error)
                            -1
                        }
                        active.lastExitCode = code
                        relay?.stopAdmission()
                        safeLog("tun2proxy завершён, код $code")
                        if (!active.stopping.get()) {
                            reportFailure(active, IllegalStateException("tun2proxy неожиданно завершён (код $code)"))
                        }
                    }.apply {
                        name = "privatevpn-tun2proxy"
                        isDaemon = true
                    }
                    active.worker = thread
                    relay?.start()
                    thread.start()
                    Thread.sleep(350)
                    if (!thread.isAlive || (relay != null && !relay.isRunning())) {
                        throw IllegalStateException(
                            "Data plane завершился сразу после старта (код ${active.lastExitCode ?: -1})"
                        )
                    }
                    safeLog("tun2proxy запущен: proxy=$socksHost:$socksPort, packetGuard=${relay != null}")
                }.onFailure {
                    newSession?.let { failed ->
                        failed.stopping.set(true)
                        stopSession(failed)
                    }
                }
            }
        }
    }

    fun isRunning(): Boolean = synchronized(lock) {
        val active = session ?: return@synchronized false
        !active.stopping.get() && active.worker?.isAlive == true &&
            (active.relay == null || active.relay.isRunning())
    }

    fun packetGuardCounters(): GuardedTunRelay.Counters? = session?.relay?.counters()

    fun stop() {
        synchronized(GLOBAL_LOCK) {
            synchronized(lock) {
                val active = session ?: return
                active.stopping.set(true)
                stopSession(active)
            }
        }
    }

    /** Both ownership locks are held; the native worker needs neither lock to return. */
    private fun stopSession(active: Session) {
        active.relay?.stopAdmission()
        val worker = active.worker
        if (worker?.isAlive == true) {
            if (nativeOwner?.manager !== this || nativeOwner?.session !== active) {
                safeLog("Остановка чужого tun2proxy runtime заблокирована")
                return
            }
            runCatching { Tun2proxy.stop() }.onFailure {
                safeLog("Не удалось запросить остановку tun2proxy")
            }
            if (worker !== Thread.currentThread()) {
                runCatching { worker.join(STOP_TIMEOUT_MS) }
            }
        }
        if (worker?.isAlive == true) {
            // Retain Session and both socketpair descriptors. A new start must not race an old
            // native runtime or hand a recycled descriptor to code that is still running.
            safeLog("tun2proxy ещё останавливается; повторный запуск заблокирован")
            return
        }
        runCatching { releaseSession(active) }.onSuccess {
            if (session === active) session = null
        }.onFailure {
            safeLog("Packet relay ещё останавливается; дескрипторы сохранены")
        }
    }

    private fun releaseSession(active: Session) {
        active.relay?.close()
        active.borrowedTun.close()
        active.relay?.counters()?.let {
            safeLog("Packet guard: admitted=${it.admittedPackets}, denied=${it.deniedPackets}")
        }
        if (nativeOwner?.manager === this && nativeOwner?.session === active) nativeOwner = null
    }

    private fun reportFailure(active: Session, error: Throwable) {
        if (active.stopping.get() || !active.failureReported.compareAndSet(false, true)) return
        active.relay?.stopAdmission()
        Thread({
            if (!active.stopping.get()) runCatching { active.onFailure(error) }
        }, "privatevpn-dataplane-failure").apply {
            isDaemon = true
            start()
        }
    }

    private fun safeLog(message: String) {
        runCatching { log(message) }
    }

    private fun buildProxyUrl(
        host: String,
        port: Int,
        username: String,
        password: String
    ): String {
        val encodedUser = encodeUserInfo(username)
        val encodedPassword = encodeUserInfo(password)
        return "socks5://$encodedUser:$encodedPassword@$host:$port"
    }

    private fun encodeUserInfo(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
            .replace("+", "%20")
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 2_000L
        val GLOBAL_LOCK = Any()
        var nativeOwner: NativeOwner? = null
    }
}
