package com.watermeter.ui.add

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.watermeter.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Экран съёмки показаний счётчика.
 *
 * — Живой preview камеры на весь экран
 * — Круглый прицел (MeterFrameOverlay) поверх preview
 * — Кнопка "Снять" внизу
 * — После снимка возвращает URI файла в intent
 */
class MeterCameraActivity : AppCompatActivity() {

    companion object {
        const val RESULT_PHOTO_URI = "photo_uri"
        private const val PERM_CODE = 102
    }

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var imageCapture: ImageCapture

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // ── Root layout ────────────────────────────────────────────────────
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        // ── Camera preview ─────────────────────────────────────────────────
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
        root.addView(previewView)

        // ── Круглый прицел поверх preview ─────────────────────────────────
        val overlay = MeterFrameOverlay(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(overlay)

        // ── Подсказка сверху ───────────────────────────────────────────────
        val tvHint = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { lp ->
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.topMargin = 80.dp
            }
            text = "Наведите циферблат в круг"
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        }
        root.addView(tvHint)

        // ── Нижняя панель: кнопка "Назад" + кнопка "Снять" ────────────────
        val bottomBar = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { lp ->
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                lp.bottomMargin = 48.dp
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // Кнопка "Назад"
        val btnBack = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(56.dp, 56.dp).also {
                it.marginEnd = 48.dp
            }
            setImageDrawable(
                ContextCompat.getDrawable(this@MeterCameraActivity, R.drawable.ic_arrow_back)
            )
            setColorFilter(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        bottomBar.addView(btnBack)

        // Кнопка "Снять" (большой белый круг)
        val btnCapture = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(72.dp, 72.dp)
            background = ContextCompat.getDrawable(
                this@MeterCameraActivity, R.drawable.bg_capture_button
            )
            setImageDrawable(
                ContextCompat.getDrawable(this@MeterCameraActivity, R.drawable.ic_camera)
            )
            setColorFilter(android.graphics.Color.parseColor("#1565C0"))
            setOnClickListener { takePhoto() }
        }
        bottomBar.addView(btnCapture)

        root.addView(bottomBar)
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), PERM_CODE
            )
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        if (!::imageCapture.isInitialized) return

        // Создаём файл для сохранения
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val photoDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val photoFile = File(photoDir, "METER_${timestamp}.jpg")

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = FileProvider.getUriForFile(
                        this@MeterCameraActivity,
                        "${packageName}.fileprovider",
                        photoFile
                    )
                    val result = Intent().apply {
                        putExtra(RESULT_PHOTO_URI, uri.toString())
                    }
                    setResult(Activity.RESULT_OK, result)
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    exception.printStackTrace()
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
            }
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_CODE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
