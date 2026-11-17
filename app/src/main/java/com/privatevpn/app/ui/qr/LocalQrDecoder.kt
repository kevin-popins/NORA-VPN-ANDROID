package com.privatevpn.app.ui.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** A scanner session accepts one result; cancellation makes queued results inert. */
internal class QrScanGate {
    private val active = AtomicBoolean(true)
    private val accepted = AtomicBoolean(false)

    val isActive: Boolean get() = active.get()

    fun tryAccept(raw: String): Boolean =
        raw.isNotBlank() && active.get() && accepted.compareAndSet(false, true) && active.get()

    fun retryUndelivered() {
        if (active.get()) accepted.set(false)
    }

    fun close() {
        active.set(false)
    }
}

/** Copies only luminance and handles camera row/pixel padding without retaining a frame. */
internal fun copyQrLuminance(
    buffer: ByteBuffer,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int
): ByteArray {
    require(width > 0 && height > 0 && rowStride > 0 && pixelStride > 0)
    require(width.toLong() * height <= Int.MAX_VALUE)
    val start = buffer.position()
    val lastPixel = start.toLong() + (height - 1).toLong() * rowStride + (width - 1).toLong() * pixelStride
    require(lastPixel < buffer.limit())
    val luminance = ByteArray(width * height)
    for (row in 0 until height) {
        val rowStart = start + row * rowStride
        for (column in 0 until width) {
            luminance[row * width + column] = buffer.get(rowStart + column * pixelStride)
        }
    }
    return luminance
}

/** Bundled QR-only decoder. No URI handling, persistence, logging, or network transport. */
internal class LocalQrDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    fun decode(luminance: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || width.toLong() * height != luminance.size.toLong()) return null
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text?.takeIf(String::isNotBlank)
        } catch (_: ReaderException) {
            try {
                reader.decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text?.takeIf(String::isNotBlank)
            } catch (_: ReaderException) {
                null
            }
        } finally {
            reader.reset()
        }
    }
}
