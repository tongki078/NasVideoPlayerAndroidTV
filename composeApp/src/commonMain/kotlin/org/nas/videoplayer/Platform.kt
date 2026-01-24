package org.nas.videoplayer

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform