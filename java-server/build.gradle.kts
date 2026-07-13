plugins {
    java
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    // PDF parsing
    implementation("org.apache.pdfbox:pdfbox:3.0.3")

    // MinIO(S3 호환) 객체 스토리지 클라이언트 — 자격증명 기반 객체 다운로드
    implementation("io.minio:minio:8.5.12")

    // PostgreSQL JDBC driver + pgvector type support
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.pgvector:pgvector:0.1.6")

    // JSON serialization for OpenAI API requests/responses
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
