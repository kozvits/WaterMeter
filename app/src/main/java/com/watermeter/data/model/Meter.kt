package com.watermeter.data.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * Счётчик холодной воды.
 *
 * @param serialNumber  Заводской номер с корпуса (распознаётся OCR или вводится вручную)
 * @param name          Пользовательское название, напр. "Кухня" / "Квартира 12"
 * @param address       Необязательный адрес/комментарий
 */
@Parcelize
@Entity(tableName = "meters")
data class Meter(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val serialNumber: String,
    val name: String,
    val address: String = "",
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable
