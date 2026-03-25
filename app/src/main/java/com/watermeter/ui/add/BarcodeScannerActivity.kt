package com.watermeter.ui.add

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.watermeter.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Полноэкранный сканер QR-кодов и штрихкодов.
 * Запускается из AddReadingFragment при тапе на иконку QR в поле номера счётчика.
 *
 * Результат: Intent с extras:
 *   RESULT_BARCODE_VALUE — String, считанное значение
 */
class BarcodeScannerActivity : AppCompatActivity() {

    companion object {
        const val RESULT_BARCODE_VALUE = "barcode_value"
        private const val PERMISSION_REQUEST_CODE = 101
    }

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var overlayView: ScannerOverlayView

    // Флаг чтобы не обрабатывать несколько результатов подряд
    private val resultDelivered = AtomicBoolean(false)
    private val barcodeScanner = BarcodeScanning.getClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Строим UI программно — без inflate, чтобы не добавлять новый layout-файл
        val root = FrameLayout(this).also {
            it.setBackgroundColor(android.graphics.Color.BLACK)
        }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
        root.addView(previewView)

        overlayView = ScannerOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(overlayView)

        tvStatus = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { lp ->
                lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                lp.bottomMargin = 120
            }
            text = "Наведите камеру на QR-код или штрихкод"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(24, 12, 24, 12)
        }
        root.addView(tvStatus)

        // Кнопка назад
        val btnBack = ImageButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(56.dp, 56.dp).also { lp ->
                lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                lp.topMargin = 32.dp
                lp.leftMargin = 16.dp
            }
            setImageDrawable(ContextCompat.getDrawable(this@BarcodeScannerActivity,
                R.drawable.ic_arrow_back))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        root.addView(btnBack)

        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), PERMISSION_REQUEST_CODE
            )
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processFrame(imageProxy: ImageProxy) {
        if (resultDelivered.get()) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                val best = barcodes
                    .sortedByDescending { b ->
                        when (b.format) {
                            Barcode.FORMAT_QR_CODE  -> 3
                            Barcode.FORMAT_CODE_128 -> 2
                            Barcode.FORMAT_CODE_39  -> 1
                            else                    -> 0
                        }
                    }
                    .firstOrNull()

                if (best != null && resultDelivered.compareAndSet(false, true)) {
                    val value = best.rawValue ?: ""
                    deliverResult(value)
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun deliverResult(value: String) {
        val intent = Intent().apply {
            putExtra(RESULT_BARCODE_VALUE, value)
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner.close()
    }
}
