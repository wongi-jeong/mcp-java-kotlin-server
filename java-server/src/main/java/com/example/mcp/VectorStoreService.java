package com.example.mcp;

import com.pgvector.PGvector;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
// PostgreSQL+pgvector CRUD (HNSW 인덱스)
public class VectorStoreService {

    // RRF(Reciprocal Rank Fusion) 상수. 관례적으로 60을 사용.
    private static final int RRF_K = 60;
    // RRF 가중치: 의미(벡터)를 주 신호로, 키워드를 보조 부스트로 둔다.
    private static final double RRF_W_VECTOR = 1.0;
    private static final double RRF_W_KEYWORD = 0.5;
    // 컷오프 우회 기준: 정확 토큰(영문/숫자/한글 단어)이 1개 이상 매칭되거나,
    // CJK 바이그램이 이 개수 이상 매칭되면 "확신 키워드 매칭"으로 보고 min_score 컷오프를 면제.
    private static final int KW_BYPASS_FUZZY_HITS = 2;
    // 키워드 토큰 추출: 공백/문장부호로 1차 분할 + 숫자/영문 토큰 별도 추출.
    private static final Pattern SPLIT = Pattern.compile("[\\s\\p{Punct}、。！？「」『』（）・…　]+");
    private static final Pattern ALNUM = Pattern.compile("[0-9A-Za-z]{2,}");
    // CJK(한자·히라가나·가타카나) 연속 구간 → 바이그램 생성 대상 (공백 없는 일본어 대응)
    private static final Pattern CJK_RUN = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]{2,}");

    private final String jdbcUrl;
    private final String user;
    private final String password;

    public VectorStoreService(String jdbcUrl, String user, String password) {
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
    }

    // DB 스키마 초기화 (서버 시작 시 1회 호출)
    public void initialize() throws SQLException {
        try (var conn = connection(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS pdf_chunks (
                        id          BIGSERIAL PRIMARY KEY,
                        object_key  VARCHAR(1024) NOT NULL,
                        chunk_index INTEGER       NOT NULL,
                        content     TEXT          NOT NULL,
                        embedding   vector(%d),
                        created_at  TIMESTAMP     DEFAULT NOW()
                    )
                    """.formatted(EmbeddingService.DIMENSIONS));
            // 기존 스키마(filename 컬럼) 호환 마이그레이션: 있으면 object_key로 이름 변경
            stmt.execute("""
                    DO $$
                    BEGIN
                        IF EXISTS (
                            SELECT 1 FROM information_schema.columns
                            WHERE table_name = 'pdf_chunks' AND column_name = 'filename'
                        ) THEN
                            ALTER TABLE pdf_chunks RENAME COLUMN filename TO object_key;
                        END IF;
                    END $$;
                    """);
            // HNSW 인덱스: 빈 테이블에도 즉시 생성 가능
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS pdf_chunks_hnsw_idx
                        ON pdf_chunks USING hnsw (embedding vector_cosine_ops)
                    """);
        }
        // 키워드(ILIKE) 검색 가속용 trigram 인덱스. pg_trgm 확장이 없으면 건너뛴다
        // (인덱스가 없어도 seq scan으로 동작은 하므로 기동 실패로 만들지 않는다).
        try (var conn = connection(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS pdf_chunks_content_trgm_idx
                        ON pdf_chunks USING gin (content gin_trgm_ops)
                    """);
        } catch (SQLException e) {
            System.err.println("[vector-store] pg_trgm 인덱스 생략(확장 미설치 가능): " + e.getMessage());
        }
    }

    // 동일 객체 키 재저장 시 기존 청크 삭제
    // 삭제된 행(청크) 수를 반환
    public int deleteByObjectKey(String objectKey) throws SQLException {
        try (var conn = connection();
             var pstmt = conn.prepareStatement("DELETE FROM pdf_chunks WHERE object_key = ?")) {
            pstmt.setString(1, objectKey);
            return pstmt.executeUpdate();
        }
    }

    public void storeChunks(String objectKey, List<String> contents, List<float[]> embeddings) throws SQLException {
        var sql = "INSERT INTO pdf_chunks (object_key, chunk_index, content, embedding) VALUES (?, ?, ?, ?)";
        try (var conn = connection(); var pstmt = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            for (int i = 0; i < contents.size(); i++) {
                pstmt.setString(1, objectKey);
                pstmt.setInt(2, i);
                pstmt.setString(3, contents.get(i));
                pstmt.setObject(4, new PGvector(embeddings.get(i)));
                pstmt.addBatch();
            }
            pstmt.executeBatch();
            conn.commit();
        }
    }

    // 하이브리드 검색: 벡터 유사도 + 키워드(ILIKE) 결과를 RRF로 융합하고,
    // 최소 유사도(minScore) 컷오프를 적용한다.
    //  - 컷오프 규칙: 확신 키워드 매칭(정확 토큰 1+ 또는 바이그램 다수)이면 유지,
    //    아니면 코사인 유사도 >= minScore 일 때만 유지. (정확 토큰이 컷오프에 걸려 사라지지 않도록)
    //  - 영문/숫자/한글 쿼리는 정확 토큰, 일본어 등 CJK는 바이그램으로 키워드 매칭한다.
    public List<SearchResult> search(float[] queryVector, String queryText, int topK, double minScore)
            throws SQLException {
        int candidateK = Math.max(topK * 5, 20);

        // 1) 벡터 후보 (거리 연산자로 정렬 → HNSW 인덱스 사용). 회수 순서가 곧 순위.
        var pool = new LinkedHashMap<String, Cand>();
        try (var conn = connection();
             var pstmt = conn.prepareStatement("""
                     SELECT object_key, chunk_index, content, 1 - (embedding <=> ?) AS score
                     FROM pdf_chunks
                     ORDER BY embedding <=> ?
                     LIMIT ?
                     """)) {
            var vec = new PGvector(queryVector);
            pstmt.setObject(1, vec);
            pstmt.setObject(2, vec);
            pstmt.setInt(3, candidateK);
            int rank = 1;
            try (var rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    var c = Cand.from(rs);
                    c.vecRank = rank++;
                    pool.put(c.key(), c);
                }
            }
        }

        // 2) 키워드 후보 (ILIKE). 정확 토큰(영문/숫자/한글 단어)과 CJK 바이그램을 함께 사용.
        //    strong_hits = 정확 토큰 매칭 수, fuzzy_hits = 바이그램 매칭 수.
        var kw = keywordTokens(queryText);
        if (!kw.isEmpty()) {
            var patterns = kw.all();                       // exact 다음 fuzzy 순서
            var sql = "SELECT object_key, chunk_index, content, 1 - (embedding <=> ?) AS score, "
                    + sumExpr(kw.exact.size()) + " AS strong_hits, "
                    + sumExpr(kw.fuzzy.size()) + " AS fuzzy_hits "
                    + "FROM pdf_chunks WHERE " + orExpr(patterns.size())
                    + " ORDER BY strong_hits DESC, fuzzy_hits DESC, score DESC LIMIT ?";
            try (var conn = connection(); var pstmt = conn.prepareStatement(sql)) {
                int idx = 1;
                pstmt.setObject(idx++, new PGvector(queryVector));            // score 계산용
                for (var t : kw.exact) pstmt.setString(idx++, "%" + t + "%"); // strong_hits 합산
                for (var t : kw.fuzzy) pstmt.setString(idx++, "%" + t + "%"); // fuzzy_hits 합산
                for (var t : patterns) pstmt.setString(idx++, "%" + t + "%"); // WHERE 절
                pstmt.setInt(idx, candidateK);
                int rank = 1;
                try (var rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        boolean strong = rs.getInt("strong_hits") >= 1
                                || rs.getInt("fuzzy_hits") >= KW_BYPASS_FUZZY_HITS;
                        var c = Cand.from(rs);
                        var existing = pool.get(c.key());
                        if (existing != null) { existing.kwRank = rank++; existing.strongKeyword = strong; }
                        else { c.kwRank = rank++; c.strongKeyword = strong; pool.put(c.key(), c); }
                    }
                }
            }
        }

        // 3) 가중 RRF 융합 + 컷오프 + 상위 topK
        var fused = new ArrayList<>(pool.values());
        for (var c : fused) {
            double rrf = 0;
            if (c.vecRank > 0) rrf += RRF_W_VECTOR  / (RRF_K + c.vecRank);
            if (c.kwRank > 0)  rrf += RRF_W_KEYWORD / (RRF_K + c.kwRank);
            c.rrf = rrf;
        }
        // 컷오프: 확신 키워드 매칭이면 유지, 아니면 코사인 유사도 >= minScore 일 때만 유지.
        // (바이그램 1개만 우연히 맞은 경우는 컷오프 대상 — 일본어 노이즈 방지)
        fused.removeIf(c -> !c.strongKeyword && c.score < minScore);
        fused.sort((a, b) -> Double.compare(b.rrf, a.rrf));

        var results = new ArrayList<SearchResult>();
        for (var c : fused.subList(0, Math.min(topK, fused.size()))) {
            var src = (c.vecRank > 0 && c.kwRank > 0) ? "hybrid"
                    : (c.kwRank > 0) ? "keyword" : "vector";
            results.add(new SearchResult(c.objectKey, c.chunkIndex, c.content, c.score, src));
        }
        return results;
    }

    // (content ILIKE ?)::int + ... 합산식. 토큰이 0개면 "0" 반환.
    private static String sumExpr(int n) {
        if (n == 0) return "0";
        var sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "(content ILIKE ?)::int" : " + (content ILIKE ?)::int");
        return sb.toString();
    }

    // content ILIKE ? OR ... 조건식.
    private static String orExpr(int n) {
        var sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(i == 0 ? "content ILIKE ?" : " OR content ILIKE ?");
        return sb.toString();
    }

    // 쿼리에서 키워드 토큰 추출.
    //  - exact: 공백/문장부호로 분리된 비-CJK 단어(영문/한글) + 숫자·영문 토큰. 정확 매칭용(고정밀).
    //  - fuzzy: CJK(한자·가나) 연속 구간의 문자 바이그램. 공백 없는 일본어 대응(둘 다 히라가나면 제외).
    static KeywordTokens keywordTokens(String queryText) {
        var kt = new KeywordTokens();
        if (queryText == null || queryText.isBlank()) return kt;
        for (var part : SPLIT.split(queryText.strip())) {
            if (part.length() >= 2 && !containsCjk(part)) kt.addExact(part);
        }
        Matcher m = ALNUM.matcher(queryText);
        while (m.find()) kt.addExact(m.group());
        Matcher r = CJK_RUN.matcher(queryText);
        while (r.find()) {
            var run = r.group();
            for (int i = 0; i + 1 < run.length(); i++) {
                char a = run.charAt(i), b = run.charAt(i + 1);
                if (isHiragana(a) && isHiragana(b)) continue;   // 조사/연결어 노이즈 제외
                kt.addFuzzy("" + a + b);
            }
        }
        return kt;
    }

    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) if (isCjk(s.charAt(i))) return true;
        return false;
    }

    private static boolean isCjk(char c) {
        var sc = Character.UnicodeScript.of(c);
        return sc == Character.UnicodeScript.HAN
                || sc == Character.UnicodeScript.HIRAGANA
                || sc == Character.UnicodeScript.KATAKANA;
    }

    private static boolean isHiragana(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA;
    }

    // 토큰 모음: exact(정확) / fuzzy(바이그램) 분리 보관, 중복 제거 및 상한 적용.
    static final class KeywordTokens {
        final List<String> exact = new ArrayList<>();
        final List<String> fuzzy = new ArrayList<>();
        void addExact(String t) { if (exact.size() < 8 && !exact.contains(t)) exact.add(t); }
        void addFuzzy(String t) { if (fuzzy.size() < 16 && !fuzzy.contains(t)) fuzzy.add(t); }
        boolean isEmpty() { return exact.isEmpty() && fuzzy.isEmpty(); }
        List<String> all() { var l = new ArrayList<String>(exact); l.addAll(fuzzy); return l; }
    }

    // 융합 단계용 내부 후보 홀더
    private static final class Cand {
        String objectKey;
        int chunkIndex;
        String content;
        double score;       // 코사인 유사도 (1 - distance)
        int vecRank = -1;
        int kwRank = -1;
        boolean strongKeyword = false;   // 정확 토큰 매칭 또는 바이그램 다수 매칭(컷오프 면제)
        double rrf = 0;

        static Cand from(ResultSet rs) throws SQLException {
            var c = new Cand();
            c.objectKey = rs.getString("object_key");
            c.chunkIndex = rs.getInt("chunk_index");
            c.content = rs.getString("content");
            c.score = rs.getDouble("score");
            return c;
        }

        String key() {
            return objectKey + " " + chunkIndex;
        }
    }

    private Connection connection() throws SQLException {
        var conn = DriverManager.getConnection(jdbcUrl, user, password);
        PGvector.addVectorType(conn);
        return conn;
    }
}
