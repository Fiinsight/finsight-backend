package com.finsight.external.kis;

import java.util.List;

public record KisMinuteCandleResult(List<KisMinuteCandle> candles, boolean fallback) {
}
