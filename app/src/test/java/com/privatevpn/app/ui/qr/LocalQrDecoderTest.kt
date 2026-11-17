package com.privatevpn.app.ui.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LocalQrDecoderTest {
    @Test
    fun `bundled decoder reads generated QR offline preserving untrusted input`() {
        val raw = "https://example.invalid/untrusted?value=do-not-open#QR"
        val pixels = qrPixels(raw)
        assertEquals(raw, LocalQrDecoder().decode(pixels, 420, 420))
    }

    @Test
    fun `inverted and rotated QR remain readable`() {
        val raw = "nora1.synthetic-local-camera-fixture"
        val pixels = qrPixels(raw)
        val inverted = ByteArray(pixels.size) { (255 - (pixels[it].toInt() and 0xff)).toByte() }
        assertEquals(raw, LocalQrDecoder().decode(inverted, 420, 420))
        val rotated = ByteArray(pixels.size)
        for (row in 0 until 420) {
            for (column in 0 until 420) {
                rotated[column * 420 + (419 - row)] = pixels[row * 420 + column]
            }
        }
        assertEquals(raw, LocalQrDecoder().decode(rotated, 420, 420))
    }

    @Test
    fun `empty or malformed frame does not produce a value`() {
        val decoder = LocalQrDecoder()
        assertNull(decoder.decode(ByteArray(100) { 127 }, 10, 10))
        assertNull(decoder.decode(ByteArray(10), 20, 20))
        assertNull(decoder.decode(ByteArray(0), 0, 0))
    }

    @Test
    fun `luminance copy respects plane offset row padding and pixel stride`() {
        val buffer = ByteBuffer.wrap(byteArrayOf(99, 1, 88, 2, 77, 66, 3, 55, 4, 44))
        buffer.position(1)
        val pixels = copyQrLuminance(buffer, width = 2, height = 2, rowStride = 5, pixelStride = 2)
        assertEquals(listOf<Byte>(1, 2, 3, 4), pixels.toList())
        assertEquals(1, buffer.position())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `truncated plane is rejected rather than reading outside buffer`() {
        copyQrLuminance(ByteBuffer.wrap(ByteArray(5)), width = 3, height = 3, rowStride = 4, pixelStride = 1)
    }

    @Test
    fun `one camera session delivers at most one result even across concurrent frames`() {
        val gate = QrScanGate()
        val start = CountDownLatch(1)
        val accepted = AtomicInteger()
        val executor = Executors.newFixedThreadPool(4)
        try {
            repeat(64) {
                executor.execute {
                    start.await()
                    if (gate.tryAccept("synthetic-qr")) accepted.incrementAndGet()
                }
            }
            start.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(1, accepted.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancellation blocks pending and future acceptance while blank frames do not consume session`() {
        val gate = QrScanGate()
        assertFalse(gate.tryAccept(" \n\t"))
        assertTrue(gate.tryAccept("synthetic-qr"))
        gate.close()
        gate.retryUndelivered()
        assertFalse(gate.isActive)
        assertFalse(gate.tryAccept("another-qr"))
    }

    @Test
    fun `callback deferred by background lifecycle may retry on resume`() {
        val gate = QrScanGate()
        assertTrue(gate.tryAccept("synthetic-qr"))
        gate.retryUndelivered()
        assertTrue(gate.tryAccept("synthetic-qr"))
        assertFalse(gate.tryAccept("duplicate"))
    }

    private fun qrPixels(raw: String): ByteArray {
        val matrix = QRCodeWriter().encode(raw, BarcodeFormat.QR_CODE, 420, 420,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        return ByteArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) 0 else 255.toByte()
        }
    }
}
