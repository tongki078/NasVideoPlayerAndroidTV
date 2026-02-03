package org.nas.videoplayerandroidtv.data

import android.content.Context
import com.squareup.sqldelight.android.AndroidSqliteDriver
import com.squareup.sqldelight.db.SqlDriver
import org.nas.videoplayerandroidtv.db.AppDatabase

// Android에서는 MainActivity에서 Context를 주입받아 드라이버를 생성하는 기존 방식을 유지합니다.
// expect/actual 일치를 위해 함수 형태를 제공합니다.
actual fun createDatabaseDriver(): SqlDriver {
    throw RuntimeException("On Android, please provide context via DatabaseDriverFactory(context)")
}

class DatabaseDriverFactory(private val context: Context) {
    fun createDriver(): SqlDriver =
        AndroidSqliteDriver(AppDatabase.Schema, context, "app.db")
}

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
