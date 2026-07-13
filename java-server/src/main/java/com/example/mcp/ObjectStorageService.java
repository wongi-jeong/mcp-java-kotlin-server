package com.example.mcp;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;

import java.io.InputStream;

// MinIO(S3 호환) 객체 스토리지 접근 래퍼.
// 자격증명 기반으로 객체를 직접 다운로드하므로 버킷을 public으로 열 필요가 없다.
public class ObjectStorageService {

    private final MinioClient client;

    public ObjectStorageService(String endpoint, String accessKey, String secretKey) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    // 환경변수 기반 생성 (없으면 로컬 기본값)
    public static ObjectStorageService fromEnv() {
        var endpoint = System.getenv().getOrDefault("MINIO_ENDPOINT", "http://localhost:9000");
        var accessKey = System.getenv().getOrDefault("MINIO_ACCESS_KEY", "minioadmin");
        var secretKey = System.getenv().getOrDefault("MINIO_SECRET_KEY", "minioadmin");
        return new ObjectStorageService(endpoint, accessKey, secretKey);
    }

    // 호출자가 스트림을 close 해야 한다.
    public InputStream getObject(String bucket, String objectKey) throws Exception {
        return client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build());
    }
}
