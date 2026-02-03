package org.nas.videoplayerandroidtv.data

import org.nas.videoplayerandroidtv.db.AppDatabase
import org.nas.videoplayerandroidtv.db.Search_history
import org.nas.videoplayerandroidtv.db.Watch_history
import kotlinx.coroutines.flow.Flow
import com.squareup.sqldelight.runtime.coroutines.asFlow
import com.squareup.sqldelight.runtime.coroutines.mapToList
import com.squareup.sqldelight.db.SqlDriver

class SearchHistoryDataSource(private val db: AppDatabase) {
    private val queries = db.searchHistoryQueries

    fun getRecentQueries(): Flow<List<Search_history>> {
        return queries.selectAll().asFlow().mapToList()
    }

    fun insertQuery(query: String, timestamp: Long) {
        queries.insertHistory(query, timestamp)
    }

    fun deleteQuery(query: String) {
        queries.deleteQuery(query)
    }

    fun clearAll() {
        queries.deleteAll()
    }
}

class WatchHistoryDataSource(private val db: AppDatabase) {
    private val queries = db.watchHistoryQueries

    fun getWatchHistory(): Flow<List<Watch_history>> {
        return queries.selectAll().asFlow().mapToList()
    }

    fun insertWatchHistory(
        id: String,
        title: String,
        videoUrl: String,
        thumbnailUrl: String?,
        timestamp: Long,
        screenType: String,
        pathStackJson: String
    ) {
        queries.insertHistory(id, title, videoUrl, thumbnailUrl, timestamp, screenType, pathStackJson)
    }

    fun deleteWatchHistory(id: String) {
        queries.deleteHistory(id)
    }
}

expect fun currentTimeMillis(): Long

fun Watch_history.toData(): WatchHistory = WatchHistory(
    id = this.id,
    title = this.title,
    videoUrl = this.videoUrl,
    thumbnailUrl = this.thumbnailUrl,
    timestamp = this.timestamp,
    screenType = this.screenType,
    pathStackJson = this.pathStackJson
)

fun Search_history.toData(): SearchHistory = SearchHistory(
    query = this.query,
    timestamp = this.timestamp
)
