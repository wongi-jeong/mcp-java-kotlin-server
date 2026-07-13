package com.example.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// ─── 기본 서비스 ───────────────────────────────────────────────────────────────

private val echoService = EchoService()
private val calculatorService = CalculatorService()

// ─── PDF 벡터 서비스 (환경변수 기반 지연 초기화) ──────────────────────────────

internal val pdfVectorService: PdfVectorService by lazy {
    val ollamaBaseUrl = System.getenv("OLLAMA_BASE_URL") ?: "http://localhost:11434"
    val ollamaModel = System.getenv("OLLAMA_MODEL") ?: "nomic-embed-text"
    val dbUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/mcpdb"
    val dbUser = System.getenv("DB_USER") ?: "postgres"
    val dbPassword = System.getenv("DB_PASSWORD") ?: ""

    val store = VectorStoreService(dbUrl, dbUser, dbPassword).also { it.initialize() }
    // ObjectStorageService: MinIO 웹훅으로 받은 객체를 자격증명으로 직접 다운로드
    PdfVectorService(
        PdfParserService(),
        EmbeddingService(ollamaBaseUrl, ollamaModel),
        store,
        ObjectStorageService.fromEnv(),
    )
}

// ─── 툴 등록 ─────────────────────────────────────────────────────────────────

fun Server.registerTools() {
    registerBasicTools()
    registerPdfTools()
}

private fun Server.registerBasicTools() {
    addTool(
        name = "echo",
        description = "Echoes the input message back to the caller.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("message") {
                    put("type", "string")
                    put("description", "The message to echo")
                }
            },
            required = listOf("message"),
        ),
    ) { request ->
        val message = request.arguments?.get("message")?.jsonPrimitive?.content ?: "(empty)"
        CallToolResult(content = listOf(TextContent(echoService.echo(message))))
    }

    addTool(
        name = "add",
        description = "Returns the sum of two numbers.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("a") {
                    put("type", "number")
                    put("description", "First operand")
                }
                putJsonObject("b") {
                    put("type", "number")
                    put("description", "Second operand")
                }
            },
            required = listOf("a", "b"),
        ),
    ) { request ->
        val a = request.arguments?.get("a")?.jsonPrimitive?.doubleOrNull
        val b = request.arguments?.get("b")?.jsonPrimitive?.doubleOrNull
        if (a == null || b == null) {
            return@addTool CallToolResult(
                content = listOf(TextContent("Both 'a' and 'b' must be numbers.")),
                isError = true,
            )
        }
        CallToolResult(content = listOf(TextContent(calculatorService.add(a, b))))
    }
}

// PDF 저장(임베딩)은 MinIO 버킷 알림(웹훅) 단일 경로로 처리한다.
// 버킷에 업로드/삭제 시 MinIO가 mcp-server로 이벤트를 보내고, 서버가 자격증명으로
// 객체를 직접 받아 자동 임베딩/정리한다(MinioWebhook + storePdfFromObject).
// 따라서 클라이언트가 직접 호출하는 저장 도구(pdf_store)는 두지 않는다.
private fun Server.registerPdfTools() {
    addTool(
        name = "pdf_search",
        description = "저장된 PDF 내용을 하이브리드(벡터 유사도 + 키워드)로 검색합니다. " +
                "최소 유사도(min_score) 미만의 순수 벡터 결과는 제외됩니다(키워드 매칭은 유지).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "검색할 질문 또는 키워드")
                }
                putJsonObject("top_k") {
                    put("type", "integer")
                    put("description", "반환할 최대 결과 수 (기본값: 5)")
                }
                putJsonObject("min_score") {
                    put("type", "number")
                    put("description", "최소 코사인 유사도 컷오프 (0~1, 기본값: 0.6). 높일수록 정밀도↑, 낮출수록 재현율↑")
                }
            },
            required = listOf("query"),
        ),
    ) { request ->
        val query = request.arguments?.get("query")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(
                content = listOf(TextContent("query 파라미터가 필요합니다.")),
                isError = true,
            )
        val topK = request.arguments?.get("top_k")?.jsonPrimitive?.intOrNull ?: 5
        val minScore = request.arguments?.get("min_score")?.jsonPrimitive?.doubleOrNull ?: 0.6

        runCatching { pdfVectorService.search(query, topK, minScore) }
            .fold(
                onSuccess = { results ->
                    if (results.isEmpty()) {
                        return@fold CallToolResult(content = listOf(TextContent("검색 결과가 없습니다.")))
                    }
                    val body = results.joinToString("\n\n---\n\n") { r ->
                        "[${r.objectKey()} | 청크 #${r.chunkIndex()} | 유사도 ${"%.4f".format(r.score())} | 매칭 ${r.matchSource()}]\n${r.content()}"
                    }
                    CallToolResult(content = listOf(TextContent(body)))
                },
                onFailure = { e ->
                    CallToolResult(content = listOf(TextContent("검색 실패: ${e.message}")), isError = true)
                },
            )
    }
}
