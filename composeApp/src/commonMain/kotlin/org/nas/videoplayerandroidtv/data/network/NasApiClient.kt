package org.nas.videoplayerandroidtv.data.network

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.compression.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

object NasApiClient {
    const val BASE_URL = "http://192.168.0.2:5000"

    val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { 
                ignoreUnknownKeys = true
                isLenient = true 
            })
        }
        // [최적화] 서버의 Gzip 압축 응답을 처리하기 위해 ContentEncoding(compression) 추가
        install(ContentEncoding) {
            gzip()
            deflate()
        }
        // 로그 레벨을 NONE으로 변경하여 대용량 데이터 처리 속도 향상
        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.NONE
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 15000
        }
    }
}
