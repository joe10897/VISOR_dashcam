package com.visor.dashcam.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.visor.dashcam.data.dao.ClipDao
import com.visor.dashcam.data.dao.EventDao
import com.visor.dashcam.data.entity.ClipEntity
import com.visor.dashcam.data.entity.EventEntity

@Database(entities = [ClipEntity::class, EventEntity::class], version = 1, exportSchema = false)
abstract class VisorDatabase : RoomDatabase() {

    abstract fun clipDao(): ClipDao
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var INSTANCE: VisorDatabase? = null

        fun getDatabase(context: Context): VisorDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VisorDatabase::class.java,
                    "visor_dashcam_offline.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
