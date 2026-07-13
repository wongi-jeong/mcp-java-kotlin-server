package com.example.mcp;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

// PDFBox로 텍스트 추출 → 단락/문장 경계 기반 의미 청킹
public class PdfParserService {

    // 청크 크기 범위: MIN 이상, MAX 이하로 유지
    // 주의: 트랜스포머 어텐션은 시퀀스 길이에 제곱 비례한다. CPU 추론에서는 청크를 키우면
    // 청크 수가 줄어도 청크당 비용이 제곱으로 커져 전체가 더 느려진다(실측 확인됨).
    private static final int MAX_SIZE = 1200;
    private static final int MIN_SIZE = 150;

    // 일본어(。！？) + 영문(. ! ?) 문장 종결 뒤 분할 — 줄바꿈 직전은 제외
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[。！？.!?])(?!\\s*\\n)");

    public List<String> extractChunks(String filePath) throws IOException {
        try (var document = Loader.loadPDF(new File(filePath))) {
            var stripper = new PDFTextStripper();
            var text = stripper.getText(document);
            return chunkSemantically(text);
        }
    }

    private List<String> chunkSemantically(String text) {
        // ① 2개 이상의 줄바꿈을 단락 경계로 인식
        var units = new ArrayList<String>();
        for (var para : text.split("(?:\\r?\\n){2,}")) {
            var p = para.strip();
            if (p.isBlank()) continue;

            if (p.length() <= MAX_SIZE) {
                units.add(p);
            } else {
                // ② 단락이 너무 길면 문장 경계에서 추가 분할
                Arrays.stream(SENTENCE_BOUNDARY.split(p))
                        .map(String::strip)
                        .filter(s -> !s.isBlank())
                        .forEach(units::add);
            }
        }

        // ③ 분할된 유닛을 [MIN_SIZE, MAX_SIZE] 범위의 청크로 묶기
        //    마지막 유닛을 다음 청크 맨 앞에 재사용해 문장 단위 overlap 확보
        var chunks = new ArrayList<String>();
        var window = new ArrayList<String>();
        int windowSize = 0;
        String overlapUnit = "";

        for (var unit : units) {
            if (windowSize + unit.length() > MAX_SIZE && windowSize >= MIN_SIZE) {
                chunks.add(String.join("\n", window));
                window.clear();
                if (!overlapUnit.isBlank()) {
                    window.add(overlapUnit);
                    windowSize = overlapUnit.length();
                } else {
                    windowSize = 0;
                }
            }
            window.add(unit);
            windowSize += unit.length();
            overlapUnit = unit;
        }

        if (!window.isEmpty()) {
            var tail = String.join("\n", window).strip();
            if (!tail.isBlank()) {
                // ④ 마지막 청크가 너무 짧으면 앞 청크에 병합
                if (tail.length() < MIN_SIZE && !chunks.isEmpty()) {
                    chunks.set(chunks.size() - 1, chunks.get(chunks.size() - 1) + "\n" + tail);
                } else {
                    chunks.add(tail);
                }
            }
        }

        return chunks;
    }
}
