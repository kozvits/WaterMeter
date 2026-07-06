package com.watermeter.util

import android.content.Context
import android.net.Uri
import com.watermeter.data.model.Meter
import com.watermeter.data.model.Reading
import com.watermeter.data.repository.MeterRepository
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

sealed class ExportImportResult {
    data class Success(val message: String) : ExportImportResult()
    data class Error(val message: String) : ExportImportResult()
}

@Singleton
class ExportImportManager @Inject constructor(
    private val repository: MeterRepository,
    private val settingsManager: SettingsManager
) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

    // ─────────────────────────────────────────────────────────────────────────
    // EXPORT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Экспортирует все данные (счётчики, показания, настройки Telegram) в JSON-файл.
     * Файл сохраняется по указанному URI (пользователь выбирает место через SAF).
     */
    suspend fun exportToUri(context: Context, uri: Uri): ExportImportResult {
        return try {
            val json = buildExportJson()
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toString(2).toByteArray(Charsets.UTF_8))
            } ?: return ExportImportResult.Error("Не удалось открыть файл для записи")

            val metersCount = repository.getMetersWithReadings().first().size
            ExportImportResult.Success("Экспорт завершён: $metersCount счётчиков")
        } catch (e: Exception) {
            ExportImportResult.Error("Ошибка экспорта: ${e.localizedMessage}")
        }
    }

    private suspend fun buildExportJson(): JSONObject {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportDate", dateFormat.format(Date()))
        root.put("appName", "WaterMeterApp")

        // ── Настройки Telegram ─────────────────────────────────────────────
        val settings = JSONObject()
        settings.put("botToken", settingsManager.botToken.first())
        settings.put("chatId",   settingsManager.chatId.first())
        root.put("settings", settings)

        // ── Счётчики и показания ───────────────────────────────────────────
        val metersArray = JSONArray()
        val allMeters = repository.getMetersWithReadings().first()

        for (mwr in allMeters) {
            val meterObj = JSONObject().apply {
                put("id",           mwr.meter.id)
                put("serialNumber", mwr.meter.serialNumber)
                put("name",         mwr.meter.name)
                put("address",      mwr.meter.address)
                put("createdAt",    mwr.meter.createdAt)
            }

            val readingsArray = JSONArray()
            val readings = mwr.readings.sortedBy { it.date }
            for (r in readings) {
                readingsArray.put(JSONObject().apply {
                    put("id",        r.id)
                    put("value",     r.value)
                    put("date",      r.date)
                    put("photoPath", r.photoPath ?: "")
                    put("note",      r.note ?: "")
                })
            }
            meterObj.put("readings", readingsArray)
            metersArray.put(meterObj)
        }
        root.put("meters", metersArray)
        return root
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IMPORT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Импортирует данные из JSON-файла по URI.
     * Стратегия: MERGE — существующие счётчики (по serialNumber) обновляются,
     * новые — добавляются. Дубликаты показаний (по дате+meterId) пропускаются.
     */
    suspend fun importFromUri(context: Context, uri: Uri): ExportImportResult {
        return try {
            val jsonStr = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: return ExportImportResult.Error("Не удалось открыть файл")

            val root = JSONObject(jsonStr)

            // Проверяем формат файла
            if (!root.has("meters")) {
                return ExportImportResult.Error("Неверный формат файла. Ожидается файл экспорта WaterMeterApp.")
            }

            // Поддержка версий — при необходимости будет расширяться
            @Suppress("UNUSED_VARIABLE")
            val fileVersion = root.optInt("version", 1)

            // ── Импорт настроек ────────────────────────────────────────────
            if (root.has("settings")) {
                val settings = root.getJSONObject("settings")
                val token  = settings.optString("botToken", "")
                val chatId = settings.optString("chatId", "")
                if (token.isNotBlank())  settingsManager.saveBotToken(token)
                if (chatId.isNotBlank()) settingsManager.saveChatId(chatId)
            }

            // ── Импорт счётчиков ───────────────────────────────────────────
            val metersJson = root.getJSONArray("meters")
            var importedMeters   = 0
            var importedReadings = 0

            // Текущие счётчики в БД — для поиска по serialNumber
            val existingMeters = repository.getMetersWithReadings().first()
            val serialToId = existingMeters.associate { it.meter.serialNumber to it.meter.id }
            val existingReadingDates = existingMeters
                .flatMap { mwr -> mwr.readings.map { "${mwr.meter.id}_${it.date}" } }
                .toHashSet()

            for (i in 0 until metersJson.length()) {
                val mObj   = metersJson.getJSONObject(i)
                val serial = mObj.optString("serialNumber", "").trim()
                if (serial.isBlank()) continue  // пропускаем записи без номера
                val name   = mObj.optString("name", serial)
                val addr   = mObj.optString("address", "")
                val createdAt = mObj.optLong("createdAt", System.currentTimeMillis())

                // Найти или создать счётчик
                val meterId: Long = if (serialToId.containsKey(serial)) {
                    serialToId[serial]!!
                } else {
                    importedMeters++
                    repository.insertMeter(
                        Meter(serialNumber = serial, name = name,
                              address = addr, createdAt = createdAt)
                    )
                }

                // Импортируем показания
                val readingsJson = mObj.optJSONArray("readings") ?: continue
                for (j in 0 until readingsJson.length()) {
                    val rObj  = readingsJson.getJSONObject(j)
                    val date  = rObj.optLong("date", System.currentTimeMillis())
                    val value = rObj.optDouble("value", -1.0)
                    if (value <= 0) continue  // пропускаем некорректные показания
                    val key   = "${meterId}_${date}"

                    if (existingReadingDates.contains(key)) continue  // пропуск дубликата

                    repository.insertReading(
                        Reading(
                            meterId   = meterId,
                            value     = value,
                            date      = date,
                            photoPath = rObj.optString("photoPath").ifBlank { null },
                            note      = rObj.optString("note").ifBlank { null }
                        )
                    )
                    importedReadings++
                }
            }

            ExportImportResult.Success(
                "Импорт завершён:\n" +
                "• Новых счётчиков: $importedMeters\n" +
                "• Новых показаний: $importedReadings"
            )
        } catch (e: Exception) {
            ExportImportResult.Error("Ошибка импорта: ${e.localizedMessage}")
        }
    }
}
