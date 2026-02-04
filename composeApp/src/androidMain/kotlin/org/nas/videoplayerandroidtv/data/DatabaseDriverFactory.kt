package org.nas.videoplayerandroidtv.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import org.nas.videoplayerandroidtv.db.AppDatabase

class DatabaseDriverFactory(private val context: Context) {
    fun createDriver(): SqlDriver =
        AndroidSqliteDriver(AppDatabase.Schema, context, "app.db")
}
