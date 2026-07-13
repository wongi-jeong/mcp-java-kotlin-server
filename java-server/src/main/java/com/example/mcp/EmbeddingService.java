package com.example.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

public class EmbeddingService {

    // 임베딩 차원. 모델 교체 시 EMBEDDING_DIMENSIONS로 맞춘다.
    //  - nomic-embed-text: 768 (기본)
    //  - all-minilm: 384
    // 차원이 바뀌면 pdf_chunks 테이블(vector(N))을 재생성해야 한다 → DB 볼륨 초기화 필요.
    public static final int DIMENSIONS =
            Integer.parseInt(System.getenv().getOrDefault("EMBEDDING_DIMENSIONS", "768"));

    // Ollama가 한 번에 처리할 최대 텍스트 수
    // 이 이상이면 서브배치로 쪼개 병렬 요청
    private static final int BATCH_SIZE = 20;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String model;

    public EmbeddingService(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model = model;
    }

    // 단일 텍스트 (검색 쿼리용) — 내부적으로 배치 API 재사용
    public float[] embed(String text) throws Exception {
        return embedBatch(List.of(text)).get(0);
    }

    // ① 배치 임베딩: BATCH_SIZE 이하면 단일 호출, 초과 시 병렬 서브배치
    public List<float[]> embedBatch(List<String> texts) throws Exception {
        if (texts.isEmpty()) return List.of();
        if (texts.size() <= BATCH_SIZE) return embedBatchDirect(texts);

        // ② BATCH_SIZE 단위로 쪼개고 Java 21 가상 스레드로 병렬 실행
        var subBatches = partition(texts, BATCH_SIZE);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = subBatches.stream()
                    .map(batch -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return embedBatchDirect(batch);
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    }, executor))
                    .toList();

            // 순서를 보장하며 결과 수집
            var results = new ArrayList<float[]>(texts.size());
            for (var future : futures) {
                results.addAll(future.get());
            }
            return results;
        }
    }

    // Ollama /api/embed — input 배열로 한 번에 전송
    private List<float[]> embedBatchDirect(List<String> texts) throws Exception {
        var body = mapper.writeValueAsString(Map.of("model", model, "input", texts));

        var request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/embed"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama API error %d: %s".formatted(response.statusCode(), response.body()));
        }

        // {"embeddings": [[0.1, ...], [0.3, ...], ...]}
        var embeddingsNode = mapper.readTree(response.body()).get("embeddings");
        var result = new ArrayList<float[]>(embeddingsNode.size());
        for (var embNode : embeddingsNode) {
            var vector = new float[embNode.size()];
            for (int i = 0; i < embNode.size(); i++) {
                vector[i] = (float) embNode.get(i).asDouble();
            }
            result.add(vector);
        }
        return result;
    }

    private static <T> List<List<T>> partition(List<T> list, int size) {
        var result = new ArrayList<List<T>>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }
}
