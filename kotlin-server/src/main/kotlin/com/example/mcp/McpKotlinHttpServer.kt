package com.example.mcp

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

// HTTP 진입점 (Ktor + Netty, Streamable HTTP transport)
fun main() {
    embeddedServer(Netty, port = 3001, host = "0.0.0.0") {
        // MinIO 버킷 알림 수신 → 업로드된 PDF 자동 임베딩
        registerMinioWebhook()

        mcpStreamableHttp("/mcp") {
            Server(
                serverInfo = Implementation(name = "kotlin-mcp-http-server", version = "1.0.0"),
                options = ServerOptions(
                    capabilities = ServerCapabilities(
                        tools = ServerCapabilities.Tools(listChanged = true),
                    ),
                ),
            ).apply { registerTools() }
        }
    }.start(wait = true)
}
