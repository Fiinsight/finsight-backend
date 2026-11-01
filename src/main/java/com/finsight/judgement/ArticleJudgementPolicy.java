package com.finsight.judgement;

import java.time.*;

/** Stale news is for review: grading always compares the day of judgement, not publication. */
public final class ArticleJudgementPolicy {
    private ArticleJudgementPolicy() {}
    public static boolean reviewOnly(Instant publishedAt, Instant now) {
        if (publishedAt == null || publishedAt.isAfter(now)) return true;
        ZoneId korea = ZoneId.of("Asia/Seoul");
        LocalDate published = publishedAt.atZone(korea).toLocalDate();
        LocalDate secondTradingDay = KoreanTradingCalendar.next(KoreanTradingCalendar.next(published));
        return !now.atZone(korea).toLocalDate().isBefore(secondTradingDay);
    }
}
