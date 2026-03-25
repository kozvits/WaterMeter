package com.watermeter.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ImageUtils {

    private const val ALBUM_NAME = "WaterMeter"

    /**
     * Создаёт временный файл для снимка камеры в приватном хранилище приложения.
     */
    fun createTempImageFile(context: Context): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("METER_${timestamp}_", ".jpg", storageDir)
    }

    /**
     * Обрезает изображение по центру (удаляет 15% с каждой стороны),
     * корректирует ориентацию через EXIF и сохраняет в галерею.
     *
     * @return URI сохранённого файла в MediaStore, либо null при ошибке.
     */
    suspend fun cropAndSaveToGallery(context: Context, sourceUri: Uri): Uri? =
        withContext(Dispatchers.IO) {
            try {
                // Читаем исходный bitmap
                val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return@withContext null
                var bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                // Корректируем ориентацию по EXIF
                bitmap = correctOrientation(context, sourceUri, bitmap)

                // Обрезка: оставляем центральные 70% по ширине и 60% по высоте
                // (типичная область с показаниями на фото счётчика)
                val cropLeft   = (bitmap.width  * 0.15).toInt()
                val cropTop    = (bitmap.height * 0.20).toInt()
                val cropWidth  = (bitmap.width  * 0.70).toInt()
                val cropHeight = (bitmap.height * 0.60).toInt()
                val cropped = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)

                // Сохраняем в галерею
                val fileName = "WM_${System.currentTimeMillis()}.jpg"
                saveBitmapToGallery(context, cropped, fileName)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    private fun correctOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
        val exif = ExifInterface(inputStream)
        inputStream.close()
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90  -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun saveBitmapToGallery(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: используем MediaStore
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri
        } else {
            // Android 9 и ниже: сохраняем в файл, обновляем MediaStore
            val picturesDir = Environment.getExternalStoragePublicDirectory(
                "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME"
            )
            picturesDir.mkdirs()
            val file = File(picturesDir, fileName)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), null, null
            )
            Uri.fromFile(file)
        }
    }
}
