package org.nas.videoplayerandroidtv.data

import org.nas.videoplayerandroidtv.TmdbMetadata
import org.nas.videoplayerandroidtv.db.AppDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class TmdbCacheDataSource(private val db: AppDatabase) {
    private val queries = db.tmdbCacheQueries
    // DB 동시 접근을 최대 3개로 제한하여 SQLiteException 방지
    private val dbSemaphore = Semaphore(3)

    suspend fun getCache(cacheKey: String): TmdbMetadata? = dbSemaphore.withPermit {
        try {
            queries.selectCache(cacheKey).executeAsOneOrNull()?.let {
                TmdbMetadata(
                    tmdbId = it.tmdbId?.toInt(),
                    mediaType = it.mediaType,
                    posterUrl = it.posterUrl,
                    backdropUrl = it.backdropUrl,
                    overview = it.overview,
                    genreIds = try { Json.decodeFromString<List<Int>>(it.genreIds ?: "[]") } catch(e: Exception) { emptyList() },
                    title = it.title
                )
            }
        } catch (e: Exception) {
            // DB 조회 중 에러 발생 시 앱이 죽지 않도록 null 반환
            null
        }
    }

    suspend fun saveCache(cacheKey: String, metadata: TmdbMetadata) = dbSemaphore.withPermit {
        try {
            queries.insertCache(
                cacheKey = cacheKey,
                tmdbId = metadata.tmdbId?.toLong(),
                mediaType = metadata.mediaType,
                posterUrl = metadata.posterUrl,
                backdropUrl = metadata.backdropUrl,
                overview = metadata.overview,
                genreIds = Json.encodeToString(metadata.genreIds),
                title = metadata.title
            )
        } catch (e: Exception) {
            // 저장 실패 시 조용히 무시
        }
    }
}
