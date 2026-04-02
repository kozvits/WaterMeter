package com.watermeter.util

import com.watermeter.data.model.Meter
import com.watermeter.data.model.Reading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed class TelegramResult {
    object Success : TelegramResult()
    data class Error(val message: String) : TelegramResult()
}

@Singleton
class TelegramSender @Inject constructor(
    private val client: OkHttpClient
) {
    private val dateFormat  = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val monthFormat = SimpleDateFormat("LLLL yyyy", Locale("ru"))

    /**
     * Отправляет ежемесячный отчёт в Telegram.
     *
     * Столбцы таблицы:
     *   № | Название | Номер счётчика | Показания (м³) | Дата
     */
    suspend fun sendMonthlyReport(
        botToken: String,
        chatId: String,
        metersWithReadings: List<Pair<Meter, Reading?>>
    ): TelegramResult = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank()) {
            return@withContext TelegramResult.Error(
                "Telegram не настроен. Укажите токен бота и Chat ID в Настройках."
            )
        }

        val message = buildMarkdownTable(metersWithReadings)

        try {
            val body = FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", message)
                .add("parse_mode", "MarkdownV2")
                .build()

            val request = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/sendMessage")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                TelegramResult.Success
            } else {
                val json = JSONObject(responseBody)
                TelegramResult.Error("Ошибка Telegram: ${json.optString("description", "Неизвестная ошибка")}")
            }
        } catch (e: Exception) {
            TelegramResult.Error("Ошибка сети: ${e.localizedMessage}")
        }
    }

    private fun buildMarkdownTable(metersWithReadings: List<Pair<Meter, Reading?>>): String {
        val now = Calendar.getInstance()
        val monthTitle = monthFormat.format(now.time).replaceFirstChar { it.uppercaseChar() }

        val sb = StringBuilder()
        sb.appendLine("📊 *Показания счётчиков воды*")
        sb.appendLine("Период: *${escapeMarkdown(monthTitle)}*")
        sb.appendLine()

        if (metersWithReadings.isEmpty()) {
            sb.appendLine("_Показания за текущий месяц не найдены_")
            return sb.toString()
        }

        // Формируем таблицу в кодовом блоке (моноширинный шрифт)
        sb.appendLine("```")

        // Вычисляем максимальные длины столбцов для выравнивания
        val maxName   = metersWithReadings.maxOf { it.first.name.length }.coerceIn(8, 16)
        val maxSerial = metersWithReadings.maxOf { it.first.serialNumber.length }.coerceIn(10, 18)

        // Шапка: № | Название | Номер счётчика | Показания | Дата
        val header = "%-3s  %-${maxName}s  %-${maxSerial}s  %-10s  %-10s".format(
            "№", "Название", "Номер счётчика", "Показания", "Дата"
        )
        sb.appendLine(header)
        sb.appendLine("-".repeat(header.length))

        metersWithReadings.forEachIndexed { index, (meter, reading) ->
            val valueStr  = reading?.let { "%.0f м³".format(it.value) } ?: "нет данных"
            val dateStr   = reading?.let { dateFormat.format(it.date) } ?: "—"
            val name      = meter.name.take(maxName)
            val serial    = meter.serialNumber.take(maxSerial)

            sb.appendLine(
                "%-3d  %-${maxName}s  %-${maxSerial}s  %-10s  %-10s".format(
                    index + 1, name, serial, valueStr, dateStr
                )
            )
        }

        sb.appendLine("-".repeat(header.length))
        sb.appendLine("Итого: ${metersWithReadings.size} счётчиков")
        sb.append("```")

        return sb.toString()
    }

    private fun escapeMarkdown(text: String): String =
        text.replace("_", "\\_").replace("*", "\\*").replace("[", "\\[")
            .replace("]", "\\]").replace("(", "\\(").replace(")", "\\)")
            .replace("~", "\\~").replace("`", "\\`").replace(">", "\\>")
            .replace("#", "\\#").replace("+", "\\+").replace("-", "\\-")
            .replace("=", "\\=").replace("|", "\\|").replace("{", "\\{")
            .replace("}", "\\}").replace(".", "\\.").replace("!", "\\!")
}
