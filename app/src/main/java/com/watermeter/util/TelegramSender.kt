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
    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val monthFormat = SimpleDateFormat("LLLL yyyy", Locale("ru"))

    /**
     * Формирует Markdown-таблицу показаний и отправляет в Telegram.
     *
     * Формат сообщения:
     * ```
     * 📊 Показания счётчиков воды
     * Период: март 2026
     *
     * | № | Номер счётчика | Название | Показания (м³) | Дата |
     * |---|----------------|----------|----------------|------|
     * | 1 | ВСХ-15-001     | Кухня    | 147.832        | 24.03.2026 |
     * ...
     * ```
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
                val desc = json.optString("description", "Неизвестная ошибка")
                TelegramResult.Error("Ошибка Telegram: $desc")
            }
        } catch (e: Exception) {
            TelegramResult.Error("Ошибка сети: ${e.localizedMessage}")
        }
    }

    private fun buildMarkdownTable(metersWithReadings: List<Pair<Meter, Reading?>>): String {
        val now = Calendar.getInstance()
        val monthTitle = monthFormat.format(now.time)
            .replaceFirstChar { it.uppercaseChar() }

        val sb = StringBuilder()

        // Заголовок
        sb.appendLine("📊 *Показания счётчиков воды*")
        sb.appendLine("Период: *${escapeMarkdown(monthTitle)}*")
        sb.appendLine()

        if (metersWithReadings.isEmpty()) {
            sb.appendLine("_Показания за текущий месяц не найдены_")
            return sb.toString()
        }

        // Таблица в виде кодового блока (моноширинный шрифт — лучшее отображение)
        sb.appendLine("```")
        // Шапка таблицы
        sb.appendLine("%-4s %-18s %-12s %-12s".format(
            "№", "Номер счётчика", "Показания", "Дата"
        ))
        sb.appendLine("-".repeat(50))

        metersWithReadings.forEachIndexed { index, (meter, reading) ->
            val valueStr = reading?.let { "%.3f м³".format(it.value) } ?: "нет данных"
            val dateStr  = reading?.let { dateFormat.format(it.date) } ?: "—"
            val serial   = meter.serialNumber.take(18)

            sb.appendLine("%-4d %-18s %-12s %-12s".format(
                index + 1, serial, valueStr, dateStr
            ))
        }

        sb.appendLine("-".repeat(50))
        sb.appendLine("Итого счётчиков: ${metersWithReadings.size}")
        sb.append("```")

        return sb.toString()
    }

    /**
     * Экранирует спецсимволы MarkdownV2 вне кодового блока.
     */
    private fun escapeMarkdown(text: String): String =
        text.replace("_", "\\_")
            .replace("*", "\\*")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("~", "\\~")
            .replace("`", "\\`")
            .replace(">", "\\>")
            .replace("#", "\\#")
            .replace("+", "\\+")
            .replace("-", "\\-")
            .replace("=", "\\=")
            .replace("|", "\\|")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace(".", "\\.")
            .replace("!", "\\!")
}
