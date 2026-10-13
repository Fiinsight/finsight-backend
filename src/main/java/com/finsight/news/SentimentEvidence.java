package com.finsight.news;

import com.finsight.briefing.SentimentHint;

public final class SentimentEvidence {
    private SentimentEvidence() {}
    public static SentimentHint direction(String hint, Double confidence, String title) {
        if (confidence != null) {
            if (!Double.isFinite(confidence) || confidence < .6 || confidence > 1) return null;
            return "POSITIVE".equals(hint) ? SentimentHint.POSITIVE : "NEGATIVE".equals(hint) ? SentimentHint.NEGATIVE : null;
        }
        boolean up = title != null && title.matches(".*(급등|상향|호실적|수혜).*"), down = title != null && title.matches(".*(급락|하향|부진|규제 강화).*" );
        return up == down ? null : up ? SentimentHint.POSITIVE : SentimentHint.NEGATIVE;
    }
    public static String note(SentimentHint hint, Double confidence) {
        if (confidence == null) return "";
        var direction = direction(hint == null ? null : hint.name(), confidence, "");
        return direction == null ? "기사 감성만으로 호재·악재를 판단하기 어려워요."
                : "기사 표현은 " + (direction == SentimentHint.POSITIVE ? "긍정적" : "부정적") + "으로 분류됐지만 주가 움직임의 원인으로 단정할 수는 없어요.";
    }
}
