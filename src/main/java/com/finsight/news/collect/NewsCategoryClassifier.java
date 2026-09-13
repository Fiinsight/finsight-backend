package com.finsight.news.collect;

import org.springframework.stereotype.Component;

/** Assigns a simple Korean category label to a headline via keyword rules. */
@Component
public class NewsCategoryClassifier {

    public String classify(String title) {
        if (title.contains("금리")) {
            return "금리정책";
        }
        if (title.contains("환율")) {
            return "환율";
        }
        if (title.contains("코스피") || title.contains("코스닥") || title.contains("증시") || title.contains("주가")) {
            return "국내증시";
        }
        if (title.contains("수출") || title.contains("무역")) {
            return "무역/수출";
        }
        return "경제/기타";
    }
}
