package com.google.ar.core.codelabs.hellogeospatial.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Database class with a singleton Instance object.
 */
@Database(entities = [Anchor::class], version = 1, exportSchema = false)
abstract class AnchorDatabase: RoomDatabase() {

    abstract fun anchorDao(): AnchorDao

    companion object {
        @Volatile
        private var Instance: AnchorDatabase? = null

        fun getDatabase(context: Context): AnchorDatabase {
            // if the Instance is not null, return it, otherwise create a new database instance.
            return Instance ?: synchronized(this) {
                Room.databaseBuilder(context, AnchorDatabase::class.java, "anchor_database")
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { Instance = it }
            }
        }
    }
}