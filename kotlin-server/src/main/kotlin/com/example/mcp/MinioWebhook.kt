package com.example.mcp

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

// 임베딩·DB 저장은 블로킹 작업이므로 요청 스레드와 분리된 IO 스코프에서 처리한다.
private val ingestScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
private val json = Json { ignoreUnknownKeys = true }

private enum class EventType { CREATED, REMOVED }

private data class ObjectEvent(val type: EventType, val bucket: String, val key: String)

// MinIO 버킷 알림(웹훅) 수신 라우트.
// pdfs 버킷에 객체가 생기거나 삭제되면 MinIO가 이 엔드포인트로 S3 이벤트 JSON을 POST한다.
//  - 생성(ObjectCreated): 자동 임베딩·저장
//  - 삭제(ObjectRemoved): 해당 파일의 벡터 청크 자동 제거
fun Application.registerMinioWebhook() {
    routing {
        post("/minio/events") {
            val body = call.receiveText()

            // 빠르게 200을 돌려주고 실제 처리는 비동기로 (MinIO 웹훅 타임아웃 방지)
            call.respond(HttpStatusCode.OK)

            val events = runCatching { parseEvents(body) }.getOrElse { e ->
                System.err.println("[minio-webhook] 이벤트 파싱 실패: ${e.message}")
                emptyList()
            }

            for (event in events) {
                ingestScope.launch { handleEvent(event) }
            }
        }
    }
}

private fun handleEvent(event: ObjectEvent) {
    val (type, bucket, key) = event
    when (type) {
        EventType.CREATED -> runCatching { pdfVectorService.storePdfFromObject(bucket, key) }
            .onSuccess { count -> System.err.println("[minio-webhook] 자동 임베딩 완료: $bucket/$key → $count 청크") }
            .onFailure { e -> System.err.println("[minio-webhook] 자동 임베딩 실패: $bucket/$key → ${e.message}") }

        EventType.REMOVED -> runCatching { pdfVectorService.deletePdfByObject(bucket, key) }
            .onSuccess { count -> System.err.println("[minio-webhook] 벡터 정리 완료: $bucket/$key → $count 청크 삭제") }
            .onFailure { e -> System.err.println("[minio-webhook] 벡터 정리 실패: $bucket/$key → ${e.message}") }
    }
}

// S3 이벤트 JSON에서 ObjectEvent 목록 추출.
// ObjectCreated / ObjectRemoved 계열 + .pdf 확장자만 대상으로 한다.
private fun parseEvents(body: String): List<ObjectEvent> {
    val records = json.parseToJsonElement(body).jsonObject["Records"]?.jsonArray ?: return emptyList()
    val result = mutableListOf<ObjectEvent>()

    for (record in records) {
        val obj = record.jsonObject
        val eventName = obj["eventName"]?.jsonPrimitive?.content ?: continue
        val type = when {
            eventName.startsWith("s3:ObjectCreated") -> EventType.CREATED
            eventName.startsWith("s3:ObjectRemoved") -> EventType.REMOVED
            else -> continue
        }

        val s3 = obj["s3"]?.jsonObject ?: continue
        val bucket = s3["bucket"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: continue
        val rawKey = s3["object"]?.jsonObject?.get("key")?.jsonPrimitive?.content ?: continue

        // S3 이벤트의 object key는 URL 인코딩되어 있다 (공백 '+', 유니코드 '%XX')
        val key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8)
        if (!key.lowercase().endsWith(".pdf")) continue

        result.add(ObjectEvent(type, bucket, key))
    }
    return result
}
