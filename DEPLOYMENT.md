# 운영 배포 — 보안 하드닝 가이드

이 문서는 운영 배포 시 적용된 보안 하드닝 4가지와 배포 절차를 정리한다.

## 적용된 하드닝

### 1. root 계정 분리 — 스코프 서비스 계정 사용
MinIO root 계정은 **부트스트랩(초기 프로비저닝) 전용**이다. 애플리케이션은 root를 쓰지 않는다.

- `mcp-readonly-*` : `pdfs` 버킷에 대해 `GetObject` / `ListBucket`만 허용 (읽기 전용). mcp-server가 사용.
- `pdf-uploader-*` : `pdfs` 버킷에 대해 `PutObject` / `GetObject` / `DeleteObject` 허용. 외부 업로더가 사용.

정책과 계정은 `minio/provision.sh`가 최초 1회 생성한다 (`mc admin policy create` + `mc admin user add` + `attach`).
mcp-server는 코드상 `getObject`만 호출하므로 읽기 전용 권한으로 충분하다.

### 2. 기본 비밀번호 교체
`minioadmin/minioadmin` 기본값을 제거하고, root 비밀번호·서비스 계정 시크릿을 강력한 랜덤 값으로 생성해 `.env`에 기록했다.
`.env`는 `.gitignore`에 포함되어 커밋되지 않는다. compose는 `${VAR:?}` 형식으로 필수 변수가 비면 기동을 거부한다.

### 3. MinIO 9000/9001 외부 비공개
`docker-compose.yml`에서 minio 서비스는 호스트로 포트를 `publish`하지 않고 `expose`(내부 전용)만 한다.
외부 접근은 **Caddy 리버스 프록시(80/443)**만 경유한다. mcp-server는 compose 네트워크 내부에서 `minio:9000`으로 직접 접근한다.

### 4. 실제 도메인 기반 MINIO_SERVER_URL
`MINIO_SERVER_URL`은 실제 도메인(`https://files.example.com` 형태)로 설정한다. presigned/share URL이 이 호스트로 발급된다.
Caddy가 해당 도메인으로 자동 HTTPS(Let's Encrypt) 인증서를 발급한다.

## 배포 전 필수 작업

1. `.env`의 도메인 자리표시자를 **실제 도메인**으로 교체:
   - `MINIO_API_DOMAIN`, `MINIO_CONSOLE_DOMAIN`
   - `MINIO_SERVER_URL` (= `https://<API_DOMAIN>`), `MINIO_BROWSER_REDIRECT_URL` (= `https://<CONSOLE_DOMAIN>`)
2. 두 도메인의 **공인 DNS A 레코드**가 이 호스트를 가리키도록 설정 (Let's Encrypt 발급 조건).
3. 방화벽에서 **80/443만 외부 개방**, 9000/9001은 개방하지 않음.
4. (선택) 사설/내부 도메인이라면 Caddyfile에서 `tls internal` 사용.

## 기동

```sh
docker compose up -d --build
# 프로비저닝 로그 확인 (정책/계정 생성)
docker compose logs minio-init
```

## 자격증명 로테이션

서비스 계정 키 교체는 root로 수행:

```sh
mc admin user remove local <old-key>
mc admin user add local <new-key> <new-secret>
mc admin policy attach local pdfs-readonly --user <new-key>
```

이후 `.env`의 해당 키/시크릿을 갱신하고 `docker compose up -d`로 재적용한다.

## 추가 권장 사항 (선택)
- pgvector(5433), mcp-server(3001) 포트도 운영에서는 내부 전용/프록시 뒤로 두는 것을 검토.
- root 콘솔 접근을 신뢰 IP로 제한.
- `.env` 파일 권한을 `600`으로 제한.
