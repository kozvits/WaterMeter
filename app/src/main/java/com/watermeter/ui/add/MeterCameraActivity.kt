package com.watermeter.ui.add

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
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
import androidx.exifinterface.media.ExifInterface
import com.watermeter.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Полноэкранная камера для съёмки показаний счётчика.
 *
 * Поверх preview отображается прямоугольный прицел (MeterFrameOverlay).
 * После нажатия кнопки "Снять":
 *   1. Делается фото
 *   2. Вычисляется область прицела в координатах битмапа
 *   3. Фото кропается по этой области
 *   4. Обрезанный файл сохраняется и его URI возвращается в Fragment
 *
 * Благодаря кропу в OCR попадают только цифры одометра.
 */
class MeterCameraActivity : AppCompatActivity() {

    companion object {
        const val RESULT_PHOTO_URI = "photo_uri"
        private const val PERM_CODE = 102
    }

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: MeterFrameOverlay
    private lateinit var imageCapture: ImageCapture

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUI()

        if (hasCameraPermission()) startCamera()
        else ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA), PERM_CODE
        )
    }

    // ── UI ─────────────────────────────────────────────────────────────────────

    private fun buildUI() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        // Живой preview камеры
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
        root.addView(previewView)

        // Прямоугольный прицел поверх preview
        overlayView = MeterFrameOverlay(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(overlayView)

        // Подсказка сверху
        val tvHint = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                it.topMargin = 56.dp
            }
            text = "Совместите цифры одометра с прямоугольником"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 2f, android.graphics.Color.BLACK)
            setPadding(16.dp, 0, 16.dp, 0)
        }
        root.addView(tvHint)

        // Нижняя панель: «Назад» + «Снять»
        val bottomBar = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also {
                it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                it.bottomMargin = 52.dp
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnBack = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(52.dp, 52.dp).also { it.marginEnd = 56.dp }
            setImageDrawable(ContextCompat.getDrawable(this@MeterCameraActivity, R.drawable.ic_arrow_back))
            setColorFilter(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        bottomBar.addView(btnBack)

        val btnCapture = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(72.dp, 72.dp)
            background = ContextCompat.getDrawable(this@MeterCameraActivity, R.drawable.bg_capture_button)
            setImageDrawable(ContextCompat.getDrawable(this@MeterCameraActivity, R.drawable.ic_camera))
            setColorFilter(android.graphics.Color.parseColor("#1565C0"))
            setOnClickListener { takePhoto() }
        }
        bottomBar.addView(btnCapture)

        root.addView(bottomBar)
        setContentView(root)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    // ── Camera ─────────────────────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            } catch (e: Exception) { e.printStackTrace() }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── Photo capture + crop ───────────────────────────────────────────────────

    private fun takePhoto() {
        if (!::imageCapture.isInitialized) return

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val fullFile = File(dir, "METER_FULL_${ts}.jpg")

        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(fullFile).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val croppedFile = cropToScanZone(fullFile, ts)
                    val resultFile = croppedFile ?: fullFile
                    val uri = FileProvider.getUriForFile(
                        this@MeterCameraActivity,
                        "${packageName}.fileprovider",
                        resultFile
                    )
                    setResult(Activity.RESULT_OK,
                        Intent().putExtra(RESULT_PHOTO_URI, uri.toString()))
                    finish()
                }
                override fun onError(e: ImageCaptureException) {
                    e.printStackTrace()
                    setResult(Activity.RESULT_CANCELED)
                    finish()
                }
            }
        )
    }

    /**
     * Кропает полное фото по зоне прицела.
     *
     * Алгоритм:
     * 1. Загружаем битмап и корректируем ориентацию EXIF
     * 2. Переводим координаты прицела (в px на экране) в координаты битмапа
     *    с учётом масштабирования preview (FIT_CENTER / CENTER_CROP)
     * 3. Обрезаем и сохраняем в отдельный файл
     */
    private fun cropToScanZone(fullFile: File, ts: String): File? {
        return try {
            // Читаем и ориентируем битмап
            var bmp = BitmapFactory.decodeFile(fullFile.absolutePath) ?: return null
            bmp = correctExifOrientation(fullFile, bmp)

            val bmpW = bmp.width.toFloat()
            val bmpH = bmp.height.toFloat()

            // Размер preview в пикселях экрана
            val pvW = previewView.width.toFloat()
            val pvH = previewView.height.toFloat()
            if (pvW == 0f || pvH == 0f) return null

            // Прямоугольник прицела в координатах overlay (= координаты экрана)
            val scanRectScreen = MeterFrameOverlay.getScanRect(
                overlayView.width, overlayView.height
            )

            // Масштаб: preview использует CENTER_CROP (или FILL_CENTER в зависимости от режима)
            // Вычисляем, как bitmap проецируется на preview
            val scaleX = bmpW / pvW
            val scaleY = bmpH / pvH
            // CENTER_CROP: берём максимальный масштаб, центрируем
            val scale = maxOf(scaleX, scaleY)
            val offsetX = (bmpW - pvW * scale) / 2f
            val offsetY = (bmpH - pvH * scale) / 2f

            // Координаты кропа в пространстве битмапа
            var cropLeft   = (scanRectScreen.left   * scale + offsetX).toInt().coerceAtLeast(0)
            var cropTop    = (scanRectScreen.top     * scale + offsetY).toInt().coerceAtLeast(0)
            var cropRight  = (scanRectScreen.right   * scale + offsetX).toInt().coerceAtMost(bmp.width)
            var cropBottom = (scanRectScreen.bottom  * scale + offsetY).toInt().coerceAtMost(bmp.height)

            // Небольшое расширение зоны (+8% по высоте) — страховка от неточности наведения
            val expandH = ((cropBottom - cropTop) * 0.08f).toInt()
            cropTop    = (cropTop    - expandH).coerceAtLeast(0)
            cropBottom = (cropBottom + expandH).coerceAtMost(bmp.height)

            val cropW = cropRight  - cropLeft
            val cropH = cropBottom - cropTop
            if (cropW <= 0 || cropH <= 0) return null

            val cropped = Bitmap.createBitmap(bmp, cropLeft, cropTop, cropW, cropH)

            // Сохраняем кроп
            val croppedFile = File(
                getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "METER_CROP_${ts}.jpg"
            )
            FileOutputStream(croppedFile).use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            croppedFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun correctExifOrientation(file: File, bmp: Bitmap): Bitmap {
        val exif = ExifInterface(file.absolutePath)
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90  -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_CODE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
