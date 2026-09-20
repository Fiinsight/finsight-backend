package com.finsight.external.kis;

import java.time.LocalDateTime;

/** A single intraday OHLC bar returned by KIS. */
public record KisMinuteCandle(
        LocalDateTime timestamp,
        double open,
        double high,
        double low,
        double close
) {
}
