#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# smoke_test.sh — 핵심 파이프라인(업로드→웹훅→임베딩)이 동작하는지 확인.
#   pdf_chunks 테이블에 임베딩된 청크가 생기는지 폴링한다.
#   사용:  bash scripts/smoke_test.sh
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")/.."

info() { printf '\033[1;34m[smoke]\033[0m %s\n' "$1"; }

info "컨테이너 상태"
docker compose ps

# pgvector 컨테이너 내부 로컬 접속은 trust 인증이라 비밀번호 없이 psql 가능.
chunk_count() {
  docker compose exec -T pgvector \
    psql -U postgres -d mcpdb -tAc "SELECT count(*) FROM pdf_chunks;" 2>/dev/null \
    | tr -d '[:space:]'
}

info "임베딩 청크 수 대기 (최초엔 모델 다운로드+임베딩으로 최대 5분)"
deadline=$(( $(date +%s) + 300 ))
count=0
while [ "$(date +%s)" -lt "$deadline" ]; do
  count="$(chunk_count || true)"
  case "$count" in ''|*[!0-9]*) count=0 ;; esac
  if [ "$count" -gt 0 ]; then
    info "OK — pdf_chunks 에 청크 ${count}개 임베딩됨 ✅"
    break
  fi
  echo "  ...대기 중 (현재 ${count}개). docker compose logs -f mcp-server 로 진행 확인 가능"
  sleep 10
done

if [ "${count:-0}" -le 0 ] 2>/dev/null; then
  echo "[smoke] 실패 — 임베딩된 청크가 없습니다." >&2
  echo "        확인: docker compose logs mcp-server ollama minio" >&2
  exit 1
fi

info "문서별 임베딩 청크 수:"
docker compose exec -T pgvector \
  psql -U postgres -d mcpdb -c \
  "SELECT object_key, count(*) AS chunks FROM pdf_chunks GROUP BY object_key ORDER BY object_key;"

info "스모크 테스트 통과 ✅"
