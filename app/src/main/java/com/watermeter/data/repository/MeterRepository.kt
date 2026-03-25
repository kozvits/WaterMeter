package com.watermeter.data.repository

import com.watermeter.data.db.MeterDao
import com.watermeter.data.model.Meter
import com.watermeter.data.model.MeterWithReadings
import com.watermeter.data.model.Reading
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeterRepository @Inject constructor(
    private val dao: MeterDao
) {

    // тФАтФА Meters тФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФА

    fun getMetersWithReadings(): Flow<List<MeterWithReadings>> =
        dao.getMetersWithReadings()

    fun getMeterWithReadings(meterId: Long): Flow<MeterWithReadings?> =
        dao.getMeterWithReadings(meterId)

    suspend fun insertMeter(meter: Meter): Long = dao.insertMeter(meter)

    suspend fun updateMeter(meter: Meter) = dao.updateMeter(meter)

    suspend fun deleteMeter(meter: Meter) = dao.deleteMeter(meter)

    suspend fun getMeterById(id: Long): Meter? = dao.getMeterById(id)

    // тФАтФА Readings тФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФАтФА

    fun getReadingsForMeter(meterId: Long): Flow<List<Reading>> =
        dao.getReadingsForMeter(meterId)

    suspend fun insertReading(reading: Reading): Long = dao.insertReading(reading)

    suspend fun updateReading(reading: Reading) = dao.updateReading(reading)

    suspend fun deleteReading(reading: Reading) = dao.deleteReading(reading)

    suspend fun getLastReading(meterId: Long): Reading? = dao.getLastReading(meterId)

    suspend fun getLatestReadingsForPeriod(from: Long, to: Long): List<Reading> =
        dao.getLatestReadingsForPeriod(from, to)
}
