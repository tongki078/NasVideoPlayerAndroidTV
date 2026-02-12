package org.nas.videoplayerandroidtv.data

import org.nas.videoplayerandroidtv.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList

class SearchHistoryDataSource(private val db: AppDatabase) {
    fun getRecentQueries(): Flow<List<SearchHistory>> {
        return try {
            db.searchHistoryQueries.selectAll()
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { list -> list.map { SearchHistory(it.query, it.timestamp) } }
                .catch { emit(emptyList()) }
        } catch (e: Exception) {
            flowOf(emptyList())
        }
    }

    suspend fun insertQuery(query: String, timestamp: Long) = withContext(Dispatchers.IO) {
        try { db.searchHistoryQueries.insertHistory(query, timestamp) } catch(_: Exception) {}
    }

    suspend fun deleteQuery(query: String) = withContext(Dispatchers.IO) {
        try { db.searchHistoryQueries.deleteQuery(query) } catch(_: Exception) {}
    }
}

class WatchHistoryDataSource(private val db: AppDatabase) {
    fun getWatchHistory(): Flow<List<WatchHistory>> {
        return try {
            db.watchHistoryQueries.selectAll()
                .asFlow()
                .mapToList(Dispatchers.IO)
                .map { list -> 
                    list.map { it.toDomainModel() } 
                }
                .catch { emit(emptyList()) }
        } catch (e: Exception) {
            flowOf(emptyList())
        }
    }

    suspend fun insertWatchHistory(
        id: String,
        title: String,
        videoUrl: String,
        thumbnailUrl: String,
        timestamp: Long,
        screenType: String,
        pathStackJson: String,
        posterPath: String?,
        lastPosition: Long,
        duration: Long,
        seriesTitle: String?,
        seriesPath: String?
    ) = withContext(Dispatchers.IO) {
        try {
            db.watchHistoryQueries.insertHistory(
                id, title, videoUrl, thumbnailUrl, timestamp, screenType, pathStackJson, posterPath, lastPosition, duration, seriesTitle, seriesPath
            )
        } catch (_: Exception) {}
    }

    suspend fun deleteWatchHistory(id: String) = withContext(Dispatchers.IO) {
        try { db.watchHistoryQueries.deleteHistory(id) } catch (_: Exception) {}
    }
}

fun org.nas.videoplayerandroidtv.db.Search_history.toDomainModel() = SearchHistory(query, timestamp)

fun org.nas.videoplayerandroidtv.db.Watch_history.toDomainModel() = WatchHistory(
    id = id,
    title = title,
    videoUrl = videoUrl,
    thumbnailUrl = thumbnailUrl,
    timestamp = timestamp,
    screenType = screenType,
    pathStackJson = pathStackJson,
    posterPath = posterPath,
    lastPosition = lastPosition,
    duration = duration,
    seriesTitle = seriesTitle,
    seriesPath = seriesPath
)
