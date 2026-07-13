# ── Stage 1: Build ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# 의존성 레이어 캐시 최적화: 소스 변경 시 재다운로드 방지
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle/ gradle/
COPY java-server/build.gradle.kts java-server/
COPY kotlin-server/build.gradle.kts kotlin-server/
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q 2>/dev/null || true

COPY java-server/src/ java-server/src/
COPY kotlin-server/src/ kotlin-server/src/
RUN ./gradlew :kotlin-server:shadowJar --no-daemon

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/kotlin-server/build/libs/kotlin-server-1.0-SNAPSHOT.jar app.jar
EXPOSE 3001
ENTRYPOINT ["java", "-jar", "app.jar"]
