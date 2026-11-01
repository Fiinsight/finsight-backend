package com.finsight.judgement;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** Official public holidays plus KRX May 1/year-end closures. No external calendar calls. */
public final class KoreanTradingCalendar {
    private KoreanTradingCalendar() {}
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KoreanTradingCalendar.class);
    private static final Set<Integer> WARNED_YEARS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // 2027 public holidays: https://www.kasa.go.kr/prog/plcyBrf/brief/kor/sub01_01_04/view.do?plcyBrfNo=431
    // Dates: https://astro.kasi.re.kr/kor/life/post/calendarData?year=2027
    // KRX rule: https://open.krx.co.kr/contents/MKD/01/0110/01100305/MKD01100305.jsp
    // shortcut: 2027 KRX special closures await the year-end notice; review when published.
    private static final Set<LocalDate> HOLIDAYS = Set.of(
        "2026-01-01", "2026-02-16", "2026-02-17", "2026-02-18", "2026-03-02",
        "2026-05-01", "2026-05-05", "2026-05-25", "2026-06-03", "2026-08-17",
        "2026-09-24", "2026-09-25", "2026-10-05", "2026-10-09", "2026-12-25", "2026-12-31",
        "2027-01-01", "2027-02-06", "2027-02-07", "2027-02-08", "2027-02-09", "2027-03-01",
        "2027-05-01", "2027-05-03", "2027-05-05", "2027-05-13", "2027-06-06", "2027-07-17", "2027-07-19",
        "2027-08-15", "2027-08-16", "2027-09-14", "2027-09-15", "2027-09-16", "2027-10-03", "2027-10-04",
        "2027-10-09", "2027-10-11", "2027-12-25", "2027-12-27", "2027-12-31"
    ).stream().map(LocalDate::parse).collect(java.util.stream.Collectors.toUnmodifiableSet());
    public static boolean isTradingDay(LocalDate day) {
        if (day.getYear() != 2026 && day.getYear() != 2027 && WARNED_YEARS.add(day.getYear()))
            log.warn("Trading holidays unavailable for {}; using weekdays until official calendar is added", day.getYear());
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
