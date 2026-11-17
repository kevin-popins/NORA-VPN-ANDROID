package com.privatevpn.app.ui.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.privatevpn.app.profiles.importer.ProfileInput
import com.privatevpn.app.profiles.importer.ProfileInputParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrImportCoordinatorTest {
    @Test
    fun `decoded profile uses actual strict classifier once even after immediate completion`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var imported = 0
        val coordinator = QrImportCoordinator(scope) { raw ->
            val classified = ProfileInputParser().parse(raw) as ProfileInput.Profiles
            imported += classified.drafts.size
        }
        try {
            val token = coordinator.beginScan()
            assertTrue(coordinator.submit(token, decodedFixture(SYNTHETIC_PROFILE)))
            assertEquals(QrImportState.Success, coordinator.state.value)
            assertEquals(1, imported)
            repeat(8) { assertFalse(coordinator.submit(token, SYNTHETIC_PROFILE)) }
            assertEquals(1, imported)
        } finally { scope.cancel() }
    }

    @Test
    fun `unsupported QR and exception payload never enter observable error state`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val raw = "<html>private-endpoint.invalid?token=synthetic-secret</html>"
        var imported = 0
        val coordinator = QrImportCoordinator(scope) { candidate ->
            try {
                ProfileInputParser().parse(candidate)
                imported += 1
            } catch (error: Exception) {
                throw IllegalStateException("Rejected private payload $candidate", error)
            }
        }
        try {
            assertTrue(coordinator.submit(coordinator.beginScan(), raw))
            val failure = coordinator.state.value as QrImportState.Failure
            assertEquals(QrImportFailureCode.UNSUPPORTED.safeMessage, failure.safeMessage)
            assertFalse(failure.toString().contains(raw))
            assertFalse(failure.safeMessage.contains("synthetic-secret"))
            assertEquals(0, imported)
        } finally { scope.cancel() }
    }

    @Test
    fun `closed stale and consumed scan tokens cannot import again`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var imports = 0
        val coordinator = QrImportCoordinator(scope) { imports += 1 }
        try {
            val closed = coordinator.beginScan()
            coordinator.closeScan(closed)
            assertFalse(coordinator.submit(closed, SYNTHETIC_PROFILE))
            val current = coordinator.beginScan()
            assertFalse(coordinator.submit(closed, SYNTHETIC_PROFILE))
            assertTrue(coordinator.submit(current, SYNTHETIC_PROFILE))
            assertFalse(coordinator.submit(current, SYNTHETIC_PROFILE))
            val latest = coordinator.beginScan()
            coordinator.closeScan(current)
            assertTrue(coordinator.submit(latest, SYNTHETIC_PROFILE))
            assertEquals(2, imports)
        } finally { scope.cancel() }
    }

    @Test
    fun `closing camera after accepting result preserves the active import`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val validated = CompletableDeferred<Unit>()
        val coordinator = QrImportCoordinator(scope) { validated.await() }
        try {
            val token = coordinator.beginScan()
            assertTrue(coordinator.submit(token, SYNTHETIC_PROFILE))
            assertEquals(QrImportState.Loading, coordinator.state.value)
            coordinator.closeScan(token)
            validated.complete(Unit)
            withTimeout(1_000) { coordinator.state.first { it == QrImportState.Success } }
            assertFalse(coordinator.submit(token, SYNTHETIC_PROFILE))
        } finally { validated.complete(Unit); scope.cancel() }
    }

    @Test
    fun `cancelled late failure cannot overwrite a newer successful import`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val releaseOld = CompletableDeferred<Unit>()
        val oldFinished = CompletableDeferred<Unit>()
        var calls = 0
        val coordinator = QrImportCoordinator(scope) { raw ->
            calls += 1
            if (calls == 1) withContext(NonCancellable) {
                try {
                    releaseOld.await()
                    throw IllegalStateException("Late private payload $raw")
                } finally { oldFinished.complete(Unit) }
            }
        }
        try {
            val oldToken = coordinator.beginScan()
            coordinator.submit(oldToken, "synthetic-old-private-input")
            coordinator.cancelImport()
            assertEquals(QrImportState.Idle, coordinator.state.value)
            assertFalse(coordinator.submit(oldToken, SYNTHETIC_PROFILE))
            assertTrue(coordinator.submit(coordinator.beginScan(), SYNTHETIC_PROFILE))
            assertEquals(QrImportState.Success, coordinator.state.value)
            releaseOld.complete(Unit)
            withTimeout(1_000) { oldFinished.await() }
            yield()
            assertEquals(QrImportState.Success, coordinator.state.value)
            assertEquals(2, calls)
        } finally { releaseOld.complete(Unit); scope.cancel() }
    }

    @Test
    fun `cancellation stops a cooperative importer and invalidates its decoder`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val stopped = CompletableDeferred<Unit>()
        val coordinator = QrImportCoordinator(scope) {
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        try {
            val token = coordinator.beginScan()
            coordinator.submit(token, SYNTHETIC_PROFILE)
            coordinator.cancelImport()
            withTimeout(1_000) { stopped.await() }
            assertEquals(QrImportState.Idle, coordinator.state.value)
            assertFalse(coordinator.submit(token, SYNTHETIC_PROFILE))
        } finally { scope.cancel() }
    }

    @Test
    fun `typed safe failure requires a fresh scan for retry`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        val coordinator = QrImportCoordinator(scope) {
            calls += 1
            if (calls == 1) throw QrImportException(QrImportFailureCode.LOAD_FAILED)
        }
        try {
            val token = coordinator.beginScan()
            coordinator.submit(token, "https://example.invalid/synthetic-subscription")
            assertEquals(QrImportState.Failure(QrImportFailureCode.LOAD_FAILED.safeMessage), coordinator.state.value)
            coordinator.dismissError()
            assertEquals(QrImportState.Idle, coordinator.state.value)
            assertFalse(coordinator.submit(token, SYNTHETIC_PROFILE))
            assertTrue(coordinator.submit(coordinator.beginScan(), SYNTHETIC_PROFILE))
            assertEquals(QrImportState.Success, coordinator.state.value)
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }

    @Test
    fun `subscription classification alone does not publish success before validated import returns`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val validatedFetch = CompletableDeferred<Unit>()
        var persisted = 0
        val coordinator = QrImportCoordinator(scope) { raw ->
            assertTrue(ProfileInputParser().parse(raw) is ProfileInput.Subscription)
            validatedFetch.await()
            persisted += 1
        }
        try {
            coordinator.submit(coordinator.beginScan(), "https://example.com/synthetic-local-test")
            assertEquals(QrImportState.Loading, coordinator.state.value)
            assertEquals(0, persisted)
            validatedFetch.complete(Unit)
            withTimeout(1_000) { coordinator.state.first { it == QrImportState.Success } }
            assertEquals(1, persisted)
        } finally { validatedFetch.complete(Unit); scope.cancel() }
    }

    private fun decodedFixture(raw: String): String {
        val matrix = QRCodeWriter().encode(raw, BarcodeFormat.QR_CODE, 420, 420)
        val luminance = ByteArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0 else 255.toByte()
        }
        return checkNotNull(LocalQrDecoder().decode(luminance, matrix.width, matrix.height))
    }

    companion object {
        private const val SYNTHETIC_PROFILE = """{"remarks":"Synthetic local QR","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"192.0.2.44","port":443,"users":[{"id":"11111111-1111-4111-8111-111111111111","encryption":"none"}]}]}}]}"""
    }
}
