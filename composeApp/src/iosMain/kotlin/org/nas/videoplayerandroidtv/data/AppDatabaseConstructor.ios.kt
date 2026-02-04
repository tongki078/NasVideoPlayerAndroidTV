package org.nas.videoplayerandroidtv.data

import com.squareup.sqldelight.db.SqlDriver
import com.squareup.sqldelight.drivers.native.NativeSqliteDriver
import org.nas.videoplayerandroidtv.db.AppDatabase

actual fun createDatabaseDriver(): SqlDriver = NativeSqliteDriver(AppDatabase.Schema, "app.db")

actual fun currentTimeMillis(): Long = kotlin.system.getTimeMillis()
