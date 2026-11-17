package com.privatevpn.app.ui.qr

import android.util.Size
import android.view.MotionEvent
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun QrCameraPreview(
    gate: QrScanGate,
    attempt: Int,
    onDecoded: (String) -> Unit,
    onCameraBound: (Camera) -> Unit,
    onFailure: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val decodedCallback = rememberUpdatedState(onDecoded)
    val boundCallback = rememberUpdatedState(onCameraBound)
    val failureCallback = rememberUpdatedState(onFailure)
    val previewView = remember(context) {
        PreviewView(context).apply {
            // Texture-backed preview clips reliably inside the rounded Compose camera scene.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            contentDescription = "Камера для сканирования QR-кода"
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)

    DisposableEffect(previewView, lifecycleOwner, attempt) {
        val disposed = AtomicBoolean(false)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder().setTargetRotation(rotation).build()
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 720))
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        var provider: ProcessCameraProvider? = null
        var camera: Camera? = null
        var cameraObserver: Observer<CameraState>? = null
        analysis.setAnalyzer(analysisExecutor, QrFrameAnalyzer(gate) { raw ->
            mainExecutor.execute {
                if (!disposed.get() && gate.isActive &&
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    gate.close()
                    decodedCallback.value(raw)
                } else {
                    gate.retryUndelivered()
                }
            }
        })
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (!disposed.get()) {
                try {
                    val availableProvider = providerFuture.get()
                    provider = availableProvider
                    val selector = when {
                        availableProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                        availableProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> null
                    }
                    if (selector == null) {
                        failureCallback.value()
                    } else {
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                        val boundCamera = availableProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                        camera = boundCamera
                        val observer = Observer<CameraState> { state ->
                            if (!disposed.get() && state.error != null) failureCallback.value()
                        }
                        cameraObserver = observer
                        boundCamera.cameraInfo.cameraState.observe(lifecycleOwner, observer)
                        previewView.setOnTouchListener { view, event ->
                            if (event.action == MotionEvent.ACTION_UP) {
                                view.performClick()
                                focusCamera(boundCamera, previewView, event.x, event.y)
                            }
                            true
                        }
                        previewView.post {
                            if (!disposed.get()) {
                                focusCamera(boundCamera, previewView, previewView.width / 2f, previewView.height / 2f)
                            }
                        }
                        boundCallback.value(boundCamera)
                    }
                } catch (_: Exception) {
                    // A missing, busy, or disabled camera is a recoverable screen state.
                    if (!disposed.get()) failureCallback.value()
                }
            }
        }, mainExecutor)

        onDispose {
            disposed.set(true)
            analysis.clearAnalyzer()
            cameraObserver?.let { observer -> camera?.cameraInfo?.cameraState?.removeObserver(observer) }
            camera?.cameraControl?.enableTorch(false)
            provider?.unbind(preview, analysis)
            previewView.setOnTouchListener(null)
            analysisExecutor.shutdownNow()
        }
    }
}

private fun focusCamera(camera: Camera, previewView: PreviewView, x: Float, y: Float) {
    if (previewView.width == 0 || previewView.height == 0) return
    try {
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        if (camera.cameraInfo.isFocusMeteringSupported(action)) {
            camera.cameraControl.startFocusAndMetering(action)
        }
    } catch (_: IllegalArgumentException) {
        // Fixed-focus cameras still decode; tapping must not interrupt the session.
    } catch (_: IllegalStateException) {
        // A tap can race with the lifecycle stopping the camera.
    }
}
