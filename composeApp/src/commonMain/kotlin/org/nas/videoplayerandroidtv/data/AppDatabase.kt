package org.nas.videoplayerandroidtv.data

import org.nas.videoplayerandroidtv.db.AppDatabase
import org.nas.videoplayerandroidtv.db.Search_history
import org.nas.videoplayerandroidtv.db.Watch_history

fun Search_history.toData(): SearchHistory = SearchHistory(
    query = this.query,
    timestamp = this.timestamp
)

fun Watch_history.toData(): WatchHistory = WatchHistory(
    id = this.id,
    title = this.title,
    videoUrl = this.videoUrl,
    thumbnailUrl = this.thumbnailUrl,
    timestamp = this.timestamp,
    screenType = this.screenType,
    pathStackJson = this.pathStackJson
)
