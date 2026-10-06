package com.finsight.history;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record RecordDateRange(Instant from, Instant to) {
    public static final String PREVIOUS_HEADER = "X-Previous-Record-Date";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static RecordDateRange parse(String from, String to) {
        if (from == null && to == null) return null;
        try {
            if (from == null || to == null || !from.matches("\\d{4}-\\d{2}-\\d{2}") || !to.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException();
            LocalDate start = LocalDate.parse(from);
            LocalDate end = LocalDate.parse(to);
            long days = ChronoUnit.DAYS.between(start, end);
            if (days < 1 || days > 7) throw new IllegalArgumentException();
            return new RecordDateRange(start.atStartOfDay(KST).toInstant(), end.atStartOfDay(KST).toInstant());
        } catch (IllegalArgumentException | DateTimeParseException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from/to는 YYYY-MM-DD 형식이며 시작일 포함·종료일 제외 최대 7일이어야 합니다.");
        }
    }

    public static String date(Instant value) {
        return value.atZone(KST).toLocalDate().toString();
    }
}
