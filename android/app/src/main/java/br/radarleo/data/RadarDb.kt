package br.radarleo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Radar::class, BaseInfo::class], version = 1, exportSchema = true)
abstract class RadarDb : RoomDatabase() {
    abstract fun radarDao(): RadarDao

    companion object {
        fun criar(contexto: Context): RadarDb =
            Room.databaseBuilder(contexto, RadarDb::class.java, "radarleo.db").build()
    }
}
