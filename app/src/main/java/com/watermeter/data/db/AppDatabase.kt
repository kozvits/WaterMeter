package com.watermeter.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.watermeter.data.model.Meter
import com.watermeter.data.model.Reading

@Database(
    entities = [Meter::class, Reading::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun meterDao(): MeterDao
}
