package com.watermeter.data.db

import androidx.room.*
import com.watermeter.data.model.Meter
import com.watermeter.data.model.MeterWithReadings
import com.watermeter.data.model.Reading
import kotlinx.coroutines.flow.Flow

@Dao
interface MeterDao {

    // ── Meters ───────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMeter(meter: Meter): Long

    @Update
    suspend fun updateMeter(meter: Meter)

    @Delete
    suspend fun deleteMeter(meter: Meter)

    @Query("SELECT * FROM meters ORDER BY name ASC")
    fun getAllMeters(): Flow<List<Meter>>

    @Query("SELECT * FROM meters WHERE id = :id")
    suspend fun getMeterById(id: Long): Meter?

    @Transaction
    @Query("SELECT * FROM meters ORDER BY name ASC")
    fun getMetersWithReadings(): Flow<List<MeterWithReadings>>

    @Transaction
    @Query("SELECT * FROM meters WHERE id = :meterId")
    fun getMeterWithReadings(meterId: Long): Flow<MeterWithReadings?>

    // ── Readings ─────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReading(reading: Reading): Long

    @Update
    suspend fun updateReading(reading: Reading)

    @Delete
    suspend fun deleteReading(reading: Reading)

    @Query("SELECT * FROM readings WHERE meterId = :meterId ORDER BY date DESC")
    fun getReadingsForMeter(meterId: Long): Flow<List<Reading>>

    @Query("SELECT * FROM readings WHERE meterId = :meterId ORDER BY date DESC LIMIT 1")
    suspend fun getLastReading(meterId: Long): Reading?

    /**
     * Последнее показание каждого счётчика за указанный период.
     * Используется для формирования Telegram-отчёта за текущий месяц.
     */
    @Query("""
        SELECT r.* FROM readings r
        INNER JOIN (
            SELECT meterId, MAX(date) AS maxDate
            FROM readings
            WHERE date >= :from AND date <= :to
            GROUP BY meterId
        ) latest ON r.meterId = latest.meterId AND r.date = latest.maxDate
        ORDER BY r.meterId ASC
    """)
    suspend fun getLatestReadingsForPeriod(from: Long, to: Long): List<Reading>
}
