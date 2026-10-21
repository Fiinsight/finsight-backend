package com.finsight.news;

import com.finsight.briefing.SentimentHint;

public final class SentimentEvidence {
    private SentimentEvidence() {}
    private static final java.util.regex.Pattern UP = java.util.regex.Pattern.compile("급등|상승|상향|강세|반등|상한가|신고가|폭등|치솟|껑충|뛰었|올라|사자|매수|상회|호실적|수혜|52주.{0,5}(?:최고|고점)|%\\s*↑");
    private static final java.util.regex.Pattern DOWN = java.util.regex.Pattern.compile("급락|하락|하향|약세|낙폭|하한가|신저가|폭락|추락|곤두박질|주저앉|내려|떨어|팔자|매도|하회|부진|우려|규제 강화|52주.{0,5}(?:최저|저점)|%\\s*↓");
    public static SentimentHint titleDirection(String title) {
        if (title == null) return null;
        int up=lastMatch(UP,title), down=lastMatch(DOWN,title);
        return up==down ? null : up>down ? SentimentHint.POSITIVE : SentimentHint.NEGATIVE;
    }
    private static int lastMatch(java.util.regex.Pattern pattern, String title) {
        var matcher=pattern.matcher(title); int last=-1;
        while (matcher.find()) last=matcher.start();
        return last;
    }
    public static SentimentHint direction(String hint, Double confidence, String title) {
        if (confidence != null) {
            if (!Double.isFinite(confidence) || confidence < .6 || confidence > 1) return null;
            return "POSITIVE".equals(hint) ? SentimentHint.POSITIVE : "NEGATIVE".equals(hint) ? SentimentHint.NEGATIVE : null;
        }
        return titleDirection(title);
    }
    public static String note(SentimentHint hint, Double confidence) {
        if (confidence == null) return "";
        var direction = direction(hint == null ? null : hint.name(), confidence, "");
        return direction == null ? "기사 감성만으로 호재·악재를 판단하기 어려워요."
                : "기사 표현은 " + (direction == SentimentHint.POSITIVE ? "긍정적" : "부정적") + "으로 분류됐지만 주가 움직임의 원인으로 단정할 수는 없어요.";
    }
}
