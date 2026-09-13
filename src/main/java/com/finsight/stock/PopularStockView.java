package com.finsight.stock;

public record PopularStockView(String symbol, String name, double price, double changePercent, boolean fallback) {
}
