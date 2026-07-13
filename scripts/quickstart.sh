#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# quickstart.sh — 처음 써보는 사람을 위한 원커맨드 로컬 실행.
#   1) Docker 확인   2) .env 자동 생성(강력한 랜덤 자격증명)   3) 코어 서비스 기동
#   4) 샘플 PDF 업로드   5) 스모크 테스트(임베딩 파이프라인 동작 확인)
#
#   사용:  bash scripts/quickstart.sh
#   운영 배포(도메인+HTTPS)는 DEPLOYMENT.md 참고.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")/.."   # 저장소 루트로 이동

info() { printf '\033[1;34m[quickstart]\033[0m %s\n' "$1"; }
die()  { printf '\033[1;31m[quickstart] ERROR:\033[0m %s\n' "$1" >&2; exit 1; }

# 1) Docker / Compose v2 확인
command -v docker >/dev/null 2>&1 || die "Docker가 필요합니다. https://docs.docker.com/get-docker/"
docker compose version >/dev/null 2>&1 || die "Docker Compose v2가 필요합니다 (docker compose)."

# 2) .env 생성 (없을 때만) — 강력한 랜덤 자격증명
if [ ! -f .env ]; then
  info ".env 생성 중 (강력한 랜덤 자격증명)"
  rand() { openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 32; }
  cat > .env <<EOF
# quickstart.sh 가 생성 (gitignored). 값 교체 시 pgvector 볼륨 초기화 필요할 수 있음.
DB_PASSWORD=$(rand)
OLLAMA_MODEL=nomic-embed-text
EMBEDDING_DIMENSIONS=768
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=$(rand)
MINIO_READONLY_KEY=mcp-readonly
MINIO_READONLY_SECRET=$(rand)
MINIO_UPLOAD_KEY=pdf-uploader
MINIO_UPLOAD_SECRET=$(rand)
# 로컬 실행에선 docker-compose.override.yml 이 아래 주소를 localhost로 덮어씀.
# (caddy는 로컬에서 띄우지 않으므로 아래 도메인은 자리표시자여도 무방)
MINIO_API_DOMAIN=files.local
MINIO_CONSOLE_DOMAIN=console.local
MINIO_SERVER_URL=http://localhost:9000
MINIO_BROWSER_REDIRECT_URL=http://localhost:9001
EOF
else
  info "기존 .env 사용"
fi

# 3) 코어 서비스 기동 (caddy 제외 — 로컬은 override가 localhost로 노출)
info "빌드 + 기동 (최초엔 임베딩 모델 다운로드로 수 분 소요될 수 있음)"
docker compose up -d --build pgvector ollama minio minio-init mcp-server

# 4) 샘플 PDF 업로드 → pdfs 버킷 (업로더 서비스 계정 사용)
#    합성 샘플(pdfs/samples/)만 사용한다. 로컬의 실제 문서로 테스트하려면
#    MinIO 콘솔이나 mc 로 직접 pdfs 버킷에 올리면 된다.
if ls pdfs/samples/*.pdf >/dev/null 2>&1; then
  info "샘플 PDF 업로드 → pdfs 버킷 (업로드 즉시 웹훅으로 자동 임베딩)"
  docker compose run --rm -T \
    -v "$(pwd)/pdfs/samples:/samples:ro" \
    --entrypoint /bin/sh minio-init -c '
      until mc alias set local http://minio:9000 "$MINIO_UPLOAD_KEY" "$MINIO_UPLOAD_SECRET" >/dev/null 2>&1; do sleep 2; done
      for f in /samples/*.pdf; do
        [ -e "$f" ] || continue
        echo "  업로드: $(basename "$f")"
        mc cp "$f" "local/pdfs/samples/$(basename "$f")" >/dev/null
      done
    '
else
  info "pdfs/ 에 샘플 PDF가 없어 업로드 생략 (MinIO 콘솔에서 직접 올리면 됩니다)"
fi

# 5) 스모크 테스트
bash scripts/smoke_test.sh

info "완료 🎉"
echo
echo "  • MCP 엔드포인트 : http://localhost:3001/mcp  (Streamable HTTP)"
echo "  • MinIO 콘솔     : http://localhost:9001  (ID: minioadmin / PW: .env의 MINIO_ROOT_PASSWORD)"
echo "  • PDF 추가       : 콘솔의 pdfs 버킷에 올리면 자동 임베딩됩니다"
echo "  • 검색           : MCP 클라이언트에서 pdf_search 도구 호출"
