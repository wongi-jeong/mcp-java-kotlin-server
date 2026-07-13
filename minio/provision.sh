#!/bin/sh
# MinIO 1회성 프로비저닝: pdfs 버킷 생성 + 웹훅 이벤트 등록 +
# 스코프 서비스 계정/정책(root 분리) 구성.
set -e

# root 자격증명으로 alias 설정 (MinIO 기동 대기)
until mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"; do
  sleep 2
done

# 버킷 + 업로드/삭제 이벤트 → mcp-server 웹훅
mc mb -p local/pdfs
mc event add --ignore-existing local/pdfs arn:minio:sqs::PDFINGEST:webhook --event put,delete

# 읽기 전용 정책: mcp-server가 pdfs 버킷 객체를 다운로드만
cat > /tmp/pdfs-readonly.json <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": ["s3:GetBucketLocation", "s3:ListBucket"], "Resource": ["arn:aws:s3:::pdfs"] },
    { "Effect": "Allow", "Action": ["s3:GetObject"], "Resource": ["arn:aws:s3:::pdfs/*"] }
  ]
}
JSON

# 업로드 정책: 외부 업로더가 pdfs 버킷에 객체 등록/조회/삭제
cat > /tmp/pdfs-upload.json <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": ["s3:GetBucketLocation", "s3:ListBucket"], "Resource": ["arn:aws:s3:::pdfs"] },
    { "Effect": "Allow", "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"], "Resource": ["arn:aws:s3:::pdfs/*"] }
  ]
}
JSON

# 정책 생성 (mc 신/구 명령 모두 대응)
mc admin policy create local pdfs-readonly /tmp/pdfs-readonly.json || mc admin policy add local pdfs-readonly /tmp/pdfs-readonly.json
mc admin policy create local pdfs-upload   /tmp/pdfs-upload.json   || mc admin policy add local pdfs-upload   /tmp/pdfs-upload.json

# 서비스 계정(사용자) 생성 — 이미 있으면 무시
mc admin user add local "$MINIO_READONLY_KEY" "$MINIO_READONLY_SECRET" || true
mc admin user add local "$MINIO_UPLOAD_KEY"   "$MINIO_UPLOAD_SECRET"   || true

# 정책 연결
mc admin policy attach local pdfs-readonly --user "$MINIO_READONLY_KEY" || true
mc admin policy attach local pdfs-upload   --user "$MINIO_UPLOAD_KEY"   || true

echo "[minio-init] provisioning done"
