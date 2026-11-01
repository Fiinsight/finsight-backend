package com.finsight.judgement;
import static org.assertj.core.api.Assertions.assertThat;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
class KoreanTradingCalendarTest {
    @Test void crossesYearEndAndNewYear() {
        for (String day : new String[]{"2026-12-30","2026-12-31"})
            assertThat(KoreanTradingCalendar.next(LocalDate.parse(day))).isEqualTo(LocalDate.parse("2027-01-04"));
    }
    @Test void skipsLunarNewYearAndSubstitutes() {
        assertThat(KoreanTradingCalendar.next(LocalDate.parse("2027-02-05"))).isEqualTo(LocalDate.parse("2027-02-10"));
        assertThat(KoreanTradingCalendar.next(LocalDate.parse("2027-05-01"))).isEqualTo(LocalDate.parse("2027-05-04"));
        assertThat(KoreanTradingCalendar.next(LocalDate.parse("2027-07-16"))).isEqualTo(LocalDate.parse("2027-07-20"));
    }
    @Test void unknownYearFallsBackWithoutThrowingAndMissingCloseIsRetryable() {
        assertThat(KoreanTradingCalendar.next(LocalDate.parse("2028-01-07"))).isEqualTo(LocalDate.parse("2028-01-10"));
        assertThat(ClosingPrices.from(new com.finsight.external.kis.KisDailyCandleResult(java.util.List.of(),false),LocalDate.parse("2028-01-07"),LocalDate.parse("2028-01-10"))).isEmpty();
    }
}
