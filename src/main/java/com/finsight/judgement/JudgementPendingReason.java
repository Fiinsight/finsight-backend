package com.finsight.judgement;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public enum JudgementPendingReason {
    NOT_DUE, PRICE_UNAVAILABLE, EXCLUDED_QA, CALENDAR_UNSUPPORTED;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static LocalDate comparisonDate(Instant judgedAt) {
        if (judgedAt == null) return null;
        var day = judgedAt.atZone(KST).toLocalDate();
        if (!KoreanTradingCalendar.supports(day.getYear())) return null;
        var next = KoreanTradingCalendar.next(day);
        return KoreanTradingCalendar.supports(next.getYear()) ? next : null;
    }

    public static JudgementPendingReason of(Judgement judgement, Instant now) {
        if (judgement.getActualDirection() != null) return null;
        if (java.util.Objects.toString(judgement.getNews().getTitle(), "").contains("로컬 QA")) return EXCLUDED_QA;
        var date = comparisonDate(judgement.getCreatedAt());
        if (date == null) return CALENDAR_UNSUPPORTED;
        return now.isBefore(date.atTime(15,40).atZone(KST).toInstant()) ? NOT_DUE : PRICE_UNAVAILABLE;
    }
}
