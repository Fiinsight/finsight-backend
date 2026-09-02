package com.finsight.external.kis;

import java.time.LocalDate;

public record KisDailyCandle(LocalDate date, double open, double high, double low, double close) {
}
