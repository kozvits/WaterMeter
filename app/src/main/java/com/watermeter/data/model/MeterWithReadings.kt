package com.watermeter.data.model

import androidx.room.Embedded
import androidx.room.Relation

data class MeterWithReadings(
    @Embedded val meter: Meter,
    @Relation(
        parentColumn = "id",
        entityColumn = "meterId"
    )
    val readings: List<Reading>
) {
    val lastReading: Reading?
        get() = readings.maxByOrNull { it.date }

    val previousReading: Reading?
        get() = readings.sortedByDescending { it.date }.getOrNull(1)

    /** Потребление за последний период (разница двух последних показаний) */
    val lastConsumption: Double?
        get() {
            val last = lastReading?.value ?: return null
            val prev = previousReading?.value ?: return null
            return (last - prev).coerceAtLeast(0.0)
        }
}
