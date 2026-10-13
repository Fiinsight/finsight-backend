package com.finsight.judgement;

import com.finsight.external.AiFeedbackRequest;
import com.finsight.external.AiFeedbackResponse;
import java.util.List;
import java.util.Locale;

/** Shared by the scheduler and the offline, read-only regrade planner. */
public final class JudgementFeedbackRules {
    public static final double MARKET_DIFFERENCE_PERCENT = 1.0;
    private JudgementFeedbackRules() {}
    public static String marketComparison(double target, double index) {
        return Math.abs(target - index) < MARKET_DIFFERENCE_PERCENT ? "MARKET" : "INDIVIDUAL";
    }
    public static String direction(double percent) {
        return percent > 0.5 ? "UP" : percent < -0.5 ? "DOWN" : "NEUTRAL";
    }
    public static String signed(double value) { return String.format(Locale.ROOT, "%+.2f%%", value); }
    public static AiFeedbackResponse generate(AiFeedbackRequest request) {
        boolean aligned = request.userChoice().equals(request.marketResult());
        double percent = request.actualChangePercent();
        String verdict = aligned && Math.abs(percent) <= 1.0 + 1e-9 ? "PARTIAL" : aligned ? "CORRECT" : "WRONG";
        String reason = first(request.userReason());
        String headline = "PARTIAL".equals(verdict) ? "방향은 맞았지만 변동이 작았어요" : "WRONG".equals(verdict)
                ? "다르게 움직였어요" : reason.isBlank() ? "예측 방향이 같았어요" : "정확한 판단이에요!";
        String result = request.relatedSymbolName() + " " + request.comparisonDate() + " 종가 기준 " + signed(percent) + ".";
        boolean indexTarget = List.of("KOSPI", "KOSDAQ").contains(request.relatedSymbol());
        boolean hasIndex = !indexTarget && request.indexChangePercent() != null;
        boolean followsMarket = hasIndex && "MARKET".equals(marketComparison(percent, request.indexChangePercent()));
        String comparison = indexTarget ? "개별 종목이 아니라 시장 전체 움직임을 비교한 결과예요."
                : !hasIndex ? "같은 구간의 지수 데이터가 없어 시장 영향을 나누어 판단하기 어려워요."
                : followsMarket ? "시장 전체 흐름과 함께 움직였어요."
                : "시장과의 차이가 커 개별 소식의 영향이 더 컸을 수 있어요.";
        String summary = result + " 예측(" + label(request.userChoice()) + ")은 실제 결과(" + label(request.marketResult()) + ")와 "
                + (aligned ? "같은" : "다른") + " 방향이에요. " + comparison;
        if ("PARTIAL".equals(verdict)) summary += " 방향은 맞았지만 변동이 작아 우연일 수 있어요.";
        if (reason.isBlank()) summary += " 다음엔 판단 근거를 한 줄 남겨보세요.";
        summary += " 가격 변화가 이 뉴스 때문에 생겼다고 단정할 수는 없어요.";
        String market = result.substring(0, result.length() - 1);
        if (request.judgementClose() != null && request.comparisonClose() != null)
            market += " (판단 기준 종가 " + request.judgementClose() + " → 결과 종가 " + request.comparisonClose() + ")";
        if (hasIndex) market += ", 같은 비교 구간 " + request.indexName() + " " + signed(request.indexChangePercent());
        if ("beginner".equals(request.level())) market += " (종가는 거래가 끝날 때의 가격이에요)";
        market += "; " + comparison;
        String core = first(request.newsSummary());
        String signal = signal(request.newsTitle() + " " + core, request.sentimentHint(), request.sentimentConfidence());
        String article = "기사 핵심 “" + core + "”에 ";
        if ("POSITIVE".equals(signal)) article += "DOWN".equals(request.marketResult()) && hasIndex && request.indexChangePercent() < -0.5
                ? "긍정적인 호재 신호가 있었지만 시장 전체 하락에 묻혔을 수 있어요."
                : "UP".equals(request.marketResult()) ? "긍정적인 호재 신호가 있고 실제 상승 방향도 같았어요."
                : "긍정적인 호재 신호가 있어도 다음 거래일 상승을 보장하지는 않아요.";
        else if ("NEGATIVE".equals(signal)) article += "DOWN".equals(request.marketResult())
                ? "악재 신호가 있고 실제 하락 방향도 같았어요."
                : "악재 신호가 있어도 다른 시장 요인과 함께 가격이 움직일 수 있어요.";
        else article += "뚜렷한 한 방향의 신호가 없어 기사 표현만으로 방향을 판단하기 어려워요.";
        String tip = indexTarget || followsMarket ? "판단 전 그날 " + (indexTarget ? request.relatedSymbolName() : request.indexName()) + " 흐름을 함께 보세요."
                : (request.newsTitle() + " " + core).matches("(?s).*(실적|매출|영업이익|호실적).*" ) ? "실적 발표 일정과 시장 예상치를 확인하세요."
                : (request.newsTitle() + " " + core).matches("(?s).*(매수|매도|수급|외국인).*" ) ? "외국인 매매와 거래량이 함께 달라졌는지 확인하세요."
                : "개별 소식의 발표 시점과 같은 날 시장 흐름을 나란히 비교해보세요.";
        return new AiFeedbackResponse(verdict, headline, summary, List.of(
                new AiFeedbackResponse.Reason("기사 신호", article),
                new AiFeedbackResponse.Reason(reason.isBlank() ? "근거 남기기" : "판단 근거", reason.isBlank() ? "다음엔 판단 근거를 한 줄 남겨보세요."
                        : "남긴 근거 “" + reason + "”로 예상한 " + label(request.userChoice()) + " 방향은 실제 결과와 " + (aligned ? "같았" : "달랐") + "어요."),
                new AiFeedbackResponse.Reason("시장 비교", market)), tip, request.conceptTerm(), request.conceptDefinition(), "RULE");
    }
    static String signal(String text, String hint, Double confidence) {
        boolean positive = text.matches("(?s).*(급등|상향|호실적|수혜|증가|개선|흑자|반등|상승|급증|치솟).*" );
        boolean negative = text.matches("(?s).*(급락|하향|부진|규제 강화|감소|악화|적자|하락|약세|우려).*" );
        if (positive != negative) return positive ? "POSITIVE" : "NEGATIVE";
        if (!positive && confidence != null && confidence >= 0.6 && hint != null) return hint;
        return "NEUTRAL";
    }
    static String first(String value) { return value == null ? "" : value.trim().split("(?<=[.!?])\\s+|\\n+", 2)[0]; }
    private static String label(String value) { return switch (value) { case "UP" -> "상승"; case "DOWN" -> "하락"; default -> "중립"; }; }
}
