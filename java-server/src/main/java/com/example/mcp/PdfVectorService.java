package com.example.mcp;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

// 세 서비스를 조율하는 파사드
public class PdfVectorService {

    private final PdfParserService parser;
    private final EmbeddingService embedding;
    private final VectorStoreService store;
    private final ObjectStorageService objectStorage;

    public PdfVectorService(PdfParserService parser, EmbeddingService embedding, VectorStoreService store) {
        this(parser, embedding, store, null);
    }

    public PdfVectorService(PdfParserService parser, EmbeddingService embedding, VectorStoreService store,
                            ObjectStorageService objectStorage) {
        this.parser = parser;
        this.embedding = embedding;
        this.store = store;
        this.objectStorage = objectStorage;
    }

    // MinIO 버킷 알림(웹훅) 기반 저장.
    // 자격증명으로 객체를 직접 받아 임시 파일로 저장 → 처리 → 삭제.
    // 식별자는 "bucket/objectKey" 전체 경로를 사용한다 (objectKey는 디코드된 실제 키).
    public int storePdfFromObject(String bucket, String objectKey) throws Exception {
        if (objectStorage == null) {
            throw new IllegalStateException("ObjectStorageService가 구성되지 않았습니다 (MINIO_* 환경변수 확인).");
        }
        var identifier = objectIdentifier(bucket, objectKey);
        var tempFile = Files.createTempFile("mcp-pdf-", ".pdf");
        try (var in = objectStorage.getObject(bucket, objectKey)) {
            // ── 단계별 소요 시간 측정 (병목 진단용) ──
            long t0 = System.nanoTime();
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            long tDownload = System.nanoTime();

            var chunks = parser.extractChunks(tempFile.toString());
            long tExtract = System.nanoTime();

            var embeddings = embedding.embedBatch(chunks);
            long tEmbed = System.nanoTime();

            store.deleteByObjectKey(identifier);
            store.storeChunks(identifier, chunks, embeddings);
            long tStore = System.nanoTime();

            System.err.printf(
                    "[timing] %s | 청크 %d개 | 다운로드 %dms · 추출 %dms · 임베딩 %dms · 저장 %dms%n",
                    identifier, chunks.size(),
                    (tDownload - t0) / 1_000_000,
                    (tExtract - tDownload) / 1_000_000,
                    (tEmbed - tExtract) / 1_000_000,
                    (tStore - tEmbed) / 1_000_000);
            return chunks.size();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // MinIO 객체 삭제 이벤트 기반 정리: 해당 객체의 벡터 청크를 DB에서 제거.
    // 객체는 이미 삭제됐으므로 다운로드 없이 식별자만으로 처리한다. 삭제된 청크 수 반환.
    public int deletePdfByObject(String bucket, String objectKey) throws Exception {
        return store.deleteByObjectKey(objectIdentifier(bucket, objectKey));
    }

    // 기본 컷오프. 0.6이면 관측된 무관 질의(~0.57)는 걸러지고, 동일 언어 적합 결과(0.67+)는 유지된다.
    // (정확 키워드 매칭은 점수와 무관하게 보존되므로 정밀도↑ 동안에도 재현율 손실이 적다.)
    public static final double DEFAULT_MIN_SCORE = 0.6;

    public List<SearchResult> search(String query, int topK) throws Exception {
        return search(query, topK, DEFAULT_MIN_SCORE);
    }

    // 하이브리드(벡터+키워드) 검색 + 최소 유사도 컷오프.
    public List<SearchResult> search(String query, int topK, double minScore) throws Exception {
        var queryVector = embedding.embed(query);
        return store.search(queryVector, query, topK, minScore);
    }

    // "bucket/objectKey" 전체 경로 식별자 생성 (objectKey는 디코드된 상태).
    // URL 경로 형식과 동일한 식별자가 되도록 통일한다.
    private static String objectIdentifier(String bucket, String objectKey) {
        var key = objectKey.startsWith("/") ? objectKey.substring(1) : objectKey;
        return bucket + "/" + key;
    }
}
