package com.finsight.judgement;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** KASI 2026 calendar + KRX May 1/year-end closures. No external calendar calls. */
public final class KoreanTradingCalendar {
    private KoreanTradingCalendar() {}
    private static final Set<LocalDate> HOLIDAYS = Set.of(
        "2026-01-01", "2026-02-16", "2026-02-17", "2026-02-18", "2026-03-02",
        "2026-05-01", "2026-05-05", "2026-05-25", "2026-06-03", "2026-08-17",
        "2026-09-24", "2026-09-25", "2026-10-05", "2026-10-09", "2026-12-25", "2026-12-31"
    ).stream().map(LocalDate::parse).collect(java.util.stream.Collectors.toUnmodifiableSet());
    public static boolean isTradingDay(LocalDate day) {
        // ponytail: verified 2026 calendar only; add next year's official closures before use.
        if (day.getYear() != 2026) throw new IllegalArgumentException("휴장일 달력이 확인되지 않은 연도입니다.");
        return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY && !HOLIDAYS.contains(day);
    }
    public static LocalDate next(LocalDate day) {
        do { day = day.plusDays(1); } while (!isTradingDay(day));
        return day;
    }
    public static LocalDate onOrBefore(LocalDate day) {
        while (!isTradingDay(day)) day = day.minusDays(1);
        return day;
    }
    public static LocalDate previous(LocalDate day) { return onOrBefore(day.minusDays(1)); }
}
