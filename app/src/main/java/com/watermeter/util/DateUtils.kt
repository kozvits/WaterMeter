package com.watermeter.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object DateUtils {

    private val displayFormat = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
    private val displayFormatShort = SimpleDateFormat("dd MMM", Locale("ru"))

    fun formatDate(timestamp: Long): String = displayFormat.format(timestamp)

    fun formatDateShort(timestamp: Long): String = displayFormatShort.format(timestamp)

    /** Начало текущего месяца (00:00:00.000) */
    fun currentMonthStart(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Конец текущего месяца (23:59:59.999) */
    fun currentMonthEnd(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.timeInMillis
}
