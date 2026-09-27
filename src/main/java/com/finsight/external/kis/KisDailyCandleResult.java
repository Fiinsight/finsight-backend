package com.finsight.external.kis;

import java.util.List;

public record KisDailyCandleResult(List<KisDailyCandle> candles, boolean fallback) {
}
