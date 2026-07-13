plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Java business-logic module
    implementation(project(":java-server"))

    // MCP Kotlin SDK — server module (includes StdioServerTransport, Server, ServerSession)
    // Brings in kotlinx-io, kotlinx-serialization, Ktor (for SSE/HTTP), and coroutines transitively
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.13.0")

    // Ktor server engine — needed for HTTP/SSE transport (Netty is the production-grade choice)
    implementation("io.ktor:ktor-server-netty:3.4.3")

    // Explicit coroutines (also transitive, pinned here for clarity)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // kotlinx-io for JVM stream bridges (InputStream.asSource / OutputStream.asSink)
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.0")

    // SLF4J — route MCP SDK logs to stderr so stdout stays clean for JSON-RPC
    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("")
    manifest {
        attributes["Main-Class"] = "com.example.mcp.McpKotlinHttpServerKt"
    }
}
