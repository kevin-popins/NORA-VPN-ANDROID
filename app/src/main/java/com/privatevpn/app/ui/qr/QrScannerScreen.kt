package com.privatevpn.app.ui.qr

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.TorchState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.privatevpn.app.ui.theme.NoraAmber
import com.privatevpn.app.ui.theme.NoraInk
import com.privatevpn.app.ui.theme.NoraInkElevated
import com.privatevpn.app.ui.theme.NoraMuted

/** Camera-only input lane. A decoded value remains untrusted input for the Add confirmation flow. */
@Composable
fun QrScannerScreen(
    onDecoded: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findQrActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val gate = remember { QrScanGate() }
    var permissionGranted by remember { mutableStateOf(context.hasQrCameraPermission()) }
    var requestAttempted by rememberSaveable { mutableStateOf(false) }
    var requestInFlight by remember { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    var settingsUnavailable by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraFailed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var torchOn by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requestInFlight = false
        permissionGranted = granted
        permanentlyDenied = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }
    fun requestCameraPermission() {
        requestAttempted = true
        requestInFlight = true
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    fun closeScanner() {
        gate.close()
        camera?.cameraControl?.enableTorch(false)
        onClose()
    }

    BackHandler(onBack = ::closeScanner)
    DisposableEffect(gate) {
        onDispose { gate.close() }
    }
    LaunchedEffect(Unit) {
        // This screen is entered only after the explicit Scan QR action.
        if (!permissionGranted && !requestAttempted) requestCameraPermission()
    }
    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) {
            camera = null
            torchOn = false
            cameraFailed = false
        }
    }
    DisposableEffect(lifecycleOwner, requestAttempted, requestInFlight, camera) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    permissionGranted = context.hasQrCameraPermission()
                    if (permissionGranted) {
                        permanentlyDenied = false
                    } else if (requestAttempted && !requestInFlight && activity != null) {
                        permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    camera?.cameraControl?.enableTorch(false)
                    torchOn = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(camera, lifecycleOwner) {
        val currentCamera = camera
        val observer = Observer<Int> { value -> torchOn = value == TorchState.ON }
        currentCamera?.cameraInfo?.torchState?.observe(lifecycleOwner, observer)
        onDispose { currentCamera?.cameraInfo?.torchState?.removeObserver(observer) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(NoraInk).safeDrawingPadding()) {
        val previewHeight = (maxHeight - 210.dp).coerceIn(200.dp, 440.dp)
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Сканировать QR",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = ::closeScanner) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть сканер")
                }
            }
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(previewHeight)
                        .clip(RoundedCornerShape(24.dp)).background(NoraInkElevated),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        !permissionGranted -> QrCameraStatePanel(
                            title = if (permanentlyDenied) "Доступ к камере выключен" else "Нужна камера",
                            description = if (permanentlyDenied) {
                                "Разрешите камеру в настройках NORA VPN или вернитесь и добавьте ключ вручную."
                            } else {
                                "Разрешите доступ, чтобы считать QR-код. Можно закрыть сканер и добавить ключ вручную."
                            },
                            action = if (permanentlyDenied) "Открыть настройки" else "Разрешить камеру",
                            enabled = !requestInFlight,
                            onAction = {
                                if (permanentlyDenied) {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", context.packageName, null)))
                                        settingsUnavailable = false
                                    } catch (_: ActivityNotFoundException) {
                                        settingsUnavailable = true
                                    }
                                } else requestCameraPermission()
                            }
                        )
                        cameraFailed -> QrCameraStatePanel(
                            title = "Камера недоступна",
                            description = "Закройте другие приложения с камерой и попробуйте ещё раз. Ключ также можно добавить вручную.",
                            action = "Повторить",
                            onAction = {
                                camera = null
                                cameraFailed = false
                                attempt += 1
                            }
                        )
                        else -> {
                            QrCameraPreview(
                                gate = gate,
                                attempt = attempt,
                                onDecoded = { raw ->
                                    camera?.cameraControl?.enableTorch(false)
                                    onDecoded(raw)
                                },
                                onCameraBound = { camera = it },
                                onFailure = {
                                    camera = null
                                    cameraFailed = true
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                            QrViewfinder(Modifier.fillMaxSize())
                            if (camera == null) {
                                Text("Подготовка камеры…", color = Color.White,
                                    modifier = Modifier.background(NoraInk.copy(alpha = 0.8f), RoundedCornerShape(12.dp)).padding(16.dp))
                            }
                        }
                    }
                }
                if (permissionGranted && !cameraFailed) {
                    Text("Наведите камеру на QR-код", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text("Автофокус · коснитесь изображения для резкости", color = NoraMuted,
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    if (camera?.cameraInfo?.hasFlashUnit() == true) {
                        OutlinedButton(
                            onClick = { camera?.cameraControl?.enableTorch(!torchOn) },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(if (torchOn) Icons.Default.FlashlightOff else Icons.Default.FlashlightOn,
                                contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(if (torchOn) "  Выключить фонарь" else "  Включить фонарь")
                        }
                    }
                }
                if (settingsUnavailable) {
                    Text("Откройте настройки приложения и разрешите камеру.", color = NoraMuted,
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                }
                Text("Распознавание на устройстве. Кадры не отправляются в сеть.",
                    color = NoraMuted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                TextButton(onClick = ::closeScanner, modifier = Modifier.height(48.dp)) {
                    Text("Отмена")
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun QrCameraStatePanel(
    title: String,
    description: String,
    action: String,
    enabled: Boolean = true,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Icon(Icons.Default.CameraAlt, contentDescription = null, tint = NoraAmber, modifier = Modifier.size(36.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(description, color = NoraMuted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Button(onClick = onAction, enabled = enabled, modifier = Modifier.height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NoraAmber, contentColor = NoraInk)) {
            Text(action, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun QrViewfinder(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = minOf(size.width, size.height) * 0.72f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val right = left + side
        val bottom = top + side
        val shade = Color.Black.copy(alpha = 0.36f)
        drawRect(shade, Offset.Zero, Size(size.width, top))
        drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
        drawRect(shade, Offset(0f, top), Size(left, side))
        drawRect(shade, Offset(right, top), Size(size.width - right, side))
        val arm = 25.dp.toPx()
        val stroke = 3.dp.toPx()
        for ((x, directionX) in listOf(left to 1f, right to -1f)) {
            for ((y, directionY) in listOf(top to 1f, bottom to -1f)) {
                drawLine(NoraAmber, Offset(x, y), Offset(x + directionX * arm, y), stroke, StrokeCap.Round)
                drawLine(NoraAmber, Offset(x, y), Offset(x, y + directionY * arm), stroke, StrokeCap.Round)
            }
        }
    }
}

private fun Context.hasQrCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findQrActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findQrActivity()
    else -> null
}
