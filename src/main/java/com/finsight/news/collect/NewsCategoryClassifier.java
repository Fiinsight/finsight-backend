package com.finsight.news.collect;

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

    public String importanceReason(String title, String storedReason) {
        if (storedReason != null && !storedReason.isBlank() && !GENERIC_REASON.equals(storedReason)) {
            return storedReason;
        }
        return switch (classify(title)) {
            case "금리·채권" -> "금리와 채권 수익률은 주식의 할인율과 자금 흐름을 바꿀 수 있습니다.";
            case "환율·원자재" -> "환율과 원자재 가격은 기업의 비용, 수익성, 외국인 수급에 영향을 줍니다.";
            case "해외증시" -> "해외 시장 흐름은 국내 증시의 투자심리와 자금 방향에 영향을 줍니다.";
            case "국내증시" -> "시장 수급과 지수 흐름은 개별 종목의 단기 방향을 판단하는 기준입니다.";
            case "산업·기술" -> "산업의 수요와 기술 변화는 관련 기업의 실적 전망을 바꿀 수 있습니다.";
            case "기업·종목" -> "기업의 실적과 사업 변화는 해당 종목의 가치와 주가에 직접 연결됩니다.";
            case "IPO·공시" -> "공시와 자본 조달 정보는 기업 가치와 투자 판단에 직접 영향을 줍니다.";
            default -> "정책과 경제 환경의 변화는 시장 전반의 기대와 위험 선호를 움직일 수 있습니다.";
        };
    }

    private boolean containsAny(String title, String... keywords) {
        for (String keyword : keywords) {
            if (title.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
