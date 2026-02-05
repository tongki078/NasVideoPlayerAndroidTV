package org.nas.videoplayerandroidtv.data

import org.nas.videoplayerandroidtv.TmdbMetadata
import org.nas.videoplayerandroidtv.db.AppDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class TmdbCacheDataSource(private val db: AppDatabase) {
    private val queries = db.tmdbCacheQueries

    fun getCache(cacheKey: String): TmdbMetadata? {
        return queries.selectCache(cacheKey).executeAsOneOrNull()?.let {
            TmdbMetadata(
                tmdbId = it.tmdbId?.toInt(), // Long? -> Int?
                mediaType = it.mediaType,
                posterUrl = it.posterUrl,
                backdropUrl = it.backdropUrl,
                overview = it.overview,
                genreIds = try { Json.decodeFromString<List<Int>>(it.genreIds ?: "[]") } catch(e: Exception) { emptyList() },
                title = it.title
            )
        }
    }

    fun saveCache(cacheKey: String, metadata: TmdbMetadata) {
        queries.insertCache(
            cacheKey = cacheKey,
            tmdbId = metadata.tmdbId?.toLong(), // Int? -> Long?
            mediaType = metadata.mediaType,
            posterUrl = metadata.posterUrl,
            backdropUrl = metadata.backdropUrl,
            overview = metadata.overview,
            genreIds = Json.encodeToString(metadata.genreIds),
            title = metadata.title
        )
    }
}
