package com.example.mcp;

// 검색 결과 레코드.
// matchSource: 어떤 경로로 회수됐는지 표시 ("vector" | "keyword" | "hybrid").
public record SearchResult(
        String objectKey,
        int chunkIndex,
        String content,
        double score,
        String matchSource
) {}
