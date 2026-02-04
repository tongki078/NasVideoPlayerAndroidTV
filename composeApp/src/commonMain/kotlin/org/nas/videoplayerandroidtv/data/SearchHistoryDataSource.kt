package org.nas.videoplayerandroidtv.data

import org.nas.videoplayerandroidtv.db.AppDatabase
import org.nas.videoplayerandroidtv.db.Search_history
import org.nas.videoplayerandroidtv.db.Watch_history
import kotlinx.coroutines.flow.Flow
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList

class SearchHistoryDataSource(private val db: AppDatabase) {
    private val queries = db.searchHistoryQueries

    fun getRecentQueries(): Flow<List<Search_history>> {
        return queries.selectAll().asFlow().mapToList(kotlin.coroutines.EmptyCoroutineContext)
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
        return queries.selectAll().asFlow().mapToList(kotlin.coroutines.EmptyCoroutineContext)
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
