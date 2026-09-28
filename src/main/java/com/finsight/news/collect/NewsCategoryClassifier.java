package com.finsight.news.collect;

import org.springframework.stereotype.Component;

/** Assigns a user-facing Korean category label to a headline via keyword rules. */
@Component
public class NewsCategoryClassifier {

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

    private boolean containsAny(String title, String... keywords) {
        for (String keyword : keywords) {
            if (title.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
