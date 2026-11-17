package com.privatevpn.app.ui.qr

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/** Processes the latest frame at most four times per second, always releasing it. */
internal class QrFrameAnalyzer(
    private val gate: QrScanGate,
    private val onDecoded: (String) -> Unit
) : ImageAnalysis.Analyzer {
    private val decoder = LocalQrDecoder()
    private var lastFrameAt = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (!gate.isActive || now - lastFrameAt < 250L) return
            lastFrameAt = now
            val plane = image.planes.firstOrNull() ?: return
            val luminance = copyQrLuminance(
                plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride
            )
            val raw = decoder.decode(luminance, image.width, image.height) ?: return
            if (gate.tryAccept(raw)) onDecoded(raw)
        } catch (_: IllegalArgumentException) {
            // Unsupported/malformed frame: skip it, never expose image data or decoder internals.
        } catch (_: IndexOutOfBoundsException) {
            // Some camera providers can invalidate a plane while the lifecycle is stopping.
        } finally {
            image.close()
        }
    }
}
