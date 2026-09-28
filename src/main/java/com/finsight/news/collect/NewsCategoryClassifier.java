package com.finsight.news.collect;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Assigns a user-facing Korean category label to a headline via keyword rules. */
@Component
public class NewsCategoryClassifier {

    private static final String GENERIC_REASON = "경제 지표 및 시장 동향과 관련된 뉴스입니다.";

    public String classify(String title) {
        if (containsAny(title, "IPO", "공시", "상장", "유상증자", "무상증자")) {
            return "IPO·공시";
        }
        if (containsAny(title, "금리", "국채", "채권", "연준", "FOMC", "기준금리")) {
            return "금리·채권";
        }
        if (containsAny(title, "환율", "원/달러", "달러", "유가", "원유", "금값", "원자재")) {
            return "환율·원자재";
        }
        if (containsAny(title, "나스닥", "다우", "S&P", "뉴욕증시", "미국 증시", "일본 증시", "중국 증시", "MSCI")) {
            return "해외증시";
        }
        if (containsAny(title, "코스피", "코스닥", "국내 증시", "증시", "주가", "장 마감", "거래소")) {
            return "국내증시";
        }
        if (containsAny(title, "반도체", "AI", "인공지능", "배터리", "바이오", "전기차", "조선", "수출", "무역")) {
            return "산업·기술";
        }
        if (containsAny(title, "삼성", "SK", "현대", "LG", "네이버", "카카오", "실적", "영업이익", "매출")) {
            return "기업·종목";
        }
        return "정책·경제";
    }

    public String importanceReason(String title, String content, String storedReason,
                                  String relatedSymbol) {
        if (storedReason != null && !storedReason.isBlank() && !GENERIC_REASON.equals(storedReason)) {
            return storedReason;
        }
        String headline = title == null ? "" : title;
        String body = content == null ? "" : content;
        Evidence evidence = firstEvidence(headline);
        if (evidence == null) {
            evidence = firstEvidence(body);
        }
        String subject = relatedSymbol != null && !relatedSymbol.isBlank()
                && !relatedSymbol.startsWith("KOS") ? "관련 종목의" : "시장";
        if (evidence == null) {
            return "기사에서 확인되는 구체적 근거가 부족해 추가 확인이 필요합니다.";
        }
        return "핵심 근거: " + evidence.keywords() + ". " + subject + " " + evidence.impact()
                + " " + evidence.check();
    }

    private Evidence firstEvidence(String text) {
        Evidence evidence = findEvidence(text, "실적과 전망 변화는",
                "실적 발표와 전망치 변화를 확인하세요.", "영업이익", "순이익", "실적", "매출", "컨센서스");
        if (evidence != null) {
            return evidence;
        }
        evidence = findEvidence(text, "수출·수주 흐름은",
                "수출액, 수주 규모와 실적 전망 변화를 확인하세요.", "수출", "수입", "무역", "주문", "계약", "수주");
        if (evidence != null) {
            return evidence;
        }
        evidence = findEvidence(text, "금리·물가 환경은",
                "기준금리 경로와 물가 지표의 변화를 확인하세요.", "금리", "기준금리", "연준", "FOMC", "물가", "인플레이션");
        if (evidence != null) {
            return evidence;
        }
        evidence = findEvidence(text, "환율·원자재 가격은",
                "원달러 환율과 원자재 가격의 추가 변화를 확인하세요.", "환율", "원/달러", "달러", "유가", "원유", "원자재");
        if (evidence != null) {
            return evidence;
        }
        evidence = findEvidence(text, "정책·규제 변화는",
                "정책 시행 시점과 적용 대상의 변화를 확인하세요.", "규제", "정책", "법안", "지원", "관세", "세제");
        if (evidence != null) {
            return evidence;
        }
        return findEvidence(text, "수요·공급 변화는",
                "판매량, 출하량과 가격의 지속성을 확인하세요.", "수요", "판매", "출하", "생산", "공급", "가격");
    }

    private Evidence findEvidence(String text, String impact, String check, String... keywords) {
        List<String> matches = new ArrayList<>();
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                matches.add(keyword);
            }
        }
        return matches.isEmpty() ? null : new Evidence(String.join(", ", matches), impact, check);
    }

    private boolean containsAny(String title, String... keywords) {
        for (String keyword : keywords) {
            if (title.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private record Evidence(String keywords, String impact, String check) {
    }
}
