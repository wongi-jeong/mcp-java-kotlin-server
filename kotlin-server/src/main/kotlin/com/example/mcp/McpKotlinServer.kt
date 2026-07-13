package com.example.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

//STDIO 진입점
fun main() {
    val server = Server(
        serverInfo = Implementation(name = "kotlin-mcp-server", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    ).apply { registerTools() }

    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered(),
    )

    runBlocking {
        val session = server.createSession(transport)
        val done = Job()
        session.onClose { done.complete() }
        done.join()
    }
}
