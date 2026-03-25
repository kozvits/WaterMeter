package com.watermeter.data.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * Одно снятие показаний счётчика.
 *
 * @param value     Показание в м³, напр. 147.832
 * @param photoPath Путь к сохранённому (обрезанному) фото; null если введено вручную
 */
@Parcelize
@Entity(
    tableName = "readings",
    foreignKeys = [
        ForeignKey(
            entity = Meter::class,
            parentColumns = ["id"],
            childColumns = ["meterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("meterId")]
)
data class Reading(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val meterId: Long,
    val value: Double,
    val date: Long = System.currentTimeMillis(),
    val photoPath: String? = null,
    val note: String? = null
) : Parcelable
