package com.milliesoft.rokid_ha.qr

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

private val WaveguideGreen = Color(0xFF00FF66)
private val WaveguideDimGreen = Color(0xFF00AA44)

@Composable
fun QrScannerScreen(
    pairingWebUrl: String?,
    onQrCodeScanned: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    var framesAnalyzed by remember { mutableIntStateOf(0) }
    var frameResolution by remember { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onCancel() }
            .padding(10.dp)
    ) {
        if (hasCameraPermission) {
            var scannedOnce by remember { mutableStateOf(false) }

            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .border(2.dp, WaveguideDimGreen, RoundedCornerShape(8.dp)),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                        val qrReader = MultiFormatReader().apply {
                            setHints(
                                mapOf(
                                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                                    DecodeHintType.TRY_HARDER to true,
                                    DecodeHintType.ALSO_INVERTED to true
                                )
                            )
                        }

                        val resolutionSelector = ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1920, 1080),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()

                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(resolutionSelector)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { imageAnalysis ->
                                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    if (!scannedOnce) {
                                        val w = imageProxy.width
                                        val h = imageProxy.height
                                        val result = decodeQrFromImageProxy(imageProxy, qrReader)
                                        previewView.post {
                                            framesAnalyzed++
                                            frameResolution = "${w}x${h}"
                                            if (result != null && !scannedOnce) {
                                                scannedOnce = true
                                                onQrCodeScanned(result)
                                            }
                                        }
                                    }
                                    imageProxy.close()
                                }
                            }

                        try {
                            cameraProvider.unbindAll()
                            val selector = when {
                                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                                    CameraSelector.DEFAULT_BACK_CAMERA
                                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                                    CameraSelector.DEFAULT_FRONT_CAMERA
                                else -> CameraSelector.Builder().build()
                            }
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                selector,
                                preview,
                                analysis
                            )
                        } catch (_: Exception) {
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )

            // Center Target Reticle
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .align(Alignment.Center)
                    .border(2.dp, WaveguideGreen, RoundedCornerShape(12.dp))
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "CAMERA PERMISSION REQUIRED FOR QR SCAN",
                    color = WaveguideGreen,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Top HUD instructions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SCAN QR ($frameResolution #$framesAnalyzed)",
                color = WaveguideGreen,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "[TAP TO EXIT]",
                color = WaveguideDimGreen,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onCancel() }
            )
        }

        // Bottom HUD alternative: Direct Wi-Fi browser URL!
        if (!pairingWebUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.9f))
                    .border(1.dp, WaveguideGreen, RoundedCornerShape(6.dp))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "OR OPEN IN PC/PHONE BROWSER: $pairingWebUrl",
                    color = WaveguideGreen,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Extracts clean Y-plane luminance accounting for Camera2 rowStride & pixelStride padding,
 * and attempts decoding in both normal and 90°-rotated orientations.
 */
private fun decodeQrFromImageProxy(image: ImageProxy, reader: MultiFormatReader): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    buffer.rewind()

    val width = image.width
    val height = image.height
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride

    val cleanY = ByteArray(width * height)
    if (rowStride == width && pixelStride == 1) {
        buffer.get(cleanY, 0, width * height)
    } else {
        val rowBuffer = ByteArray(rowStride)
        for (row in 0 until height) {
            val bytesToRead = if (row == height - 1) {
                buffer.remaining().coerceAtMost(rowStride)
            } else {
                rowStride
            }
            buffer.get(rowBuffer, 0, bytesToRead)
            if (pixelStride == 1) {
                System.arraycopy(rowBuffer, 0, cleanY, row * width, width)
            } else {
                for (col in 0 until width) {
                    cleanY[row * width + col] = rowBuffer[col * pixelStride]
                }
            }
        }
    }

    // 1. Try normal orientation (Hybrid + GlobalHistogram)
    tryDecodeLuminance(cleanY, width, height, reader)?.let { return it }

    // 2. Try 90-degree rotated orientation (in case sensor is portrait-mounted)
    val rotatedY = ByteArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            rotatedY[x * height + (height - 1 - y)] = cleanY[y * width + x]
        }
    }
    return tryDecodeLuminance(rotatedY, height, width, reader)
}

private fun tryDecodeLuminance(
    yBytes: ByteArray,
    width: Int,
    height: Int,
    reader: MultiFormatReader
): String? {
    val source = PlanarYUVLuminanceSource(yBytes, width, height, 0, 0, width, height, false)

    try {
        return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: Exception) {
        reader.reset()
    }

    try {
        return reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source))).text
    } catch (_: Exception) {
        reader.reset()
    }

    return null
}
