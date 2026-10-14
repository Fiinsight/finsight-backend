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
    private static final java.util.Set<String> GENERIC_PREFIXES = java.util.Set.of("예상","생각","주가","상승","하락","예측","같아","같다","있어","있는","있을","것같","그냥","때문","그래");
    private static final java.util.regex.Pattern REASON_WORDS = java.util.regex.Pattern.compile("[가-힣A-Za-z0-9]{2,}");
    private static final java.util.regex.Pattern LINK_WORDS = java.util.regex.Pattern.compile("실적|이익|매출|수요|공급|수급|매수|매도|금리|비용|기대|계약|수주|환율|외국인|규제|성장|원가|유가");

    static String interpretation(String signal, String direction, Double percent, Double indexChange) {
        if ("UNKNOWN".equals(direction)) return "실제 가격 결과가 없어 기사 신호가 반영됐는지는 아직 확인할 수 없어요.";
        if (percent != null && Math.abs(percent) <= 0.5) return "변동이 작아 시장이 기사 신호를 크게 반영하지 않았을 수 있어요.";
        if ("POSITIVE".equals(signal)) {
            if ("UP".equals(direction)) return "기사의 호재 신호대로 실제 상승 방향도 같았어요.";
            if ("DOWN".equals(direction)) return indexChange != null && indexChange < -0.5
                    ? "긍정적인 호재 신호가 있었지만 시장 전체 하락에 묻혔을 수 있어요."
                    : "호재가 이미 가격에 선반영됐거나 다른 악재가 함께 작용해 실제로는 하락했을 수 있어요.";
            return "호재 신호가 있어도 가격이 중립 범위에 머물러 기대가 이미 반영됐을 수 있어요.";
        }
        if ("NEGATIVE".equals(signal)) {
            if ("DOWN".equals(direction)) return "기사의 악재 신호대로 실제 하락 방향도 같았어요.";
            if ("UP".equals(direction)) return "악재가 이미 가격에 선반영됐거나 우려보다 덜 나쁘다는 안도감이 상승과 함께 작용했을 수 있어요.";
            return "악재 신호가 있어도 가격이 중립 범위에 머물러 우려가 이미 반영됐을 수 있어요.";
        }
        return "기사에 뚜렷한 방향 신호가 없어 개별 소식보다 시장 흐름이나 수급을 따라 움직였을 수 있어요.";
    }

    private static String choiceCheck(String signal, String choice) {
        if (choice == null) return "선택한 방향 정보가 없어 기사 신호와의 일치 여부는 확인하기 어려워요.";
        if ("NEUTRAL".equals(signal)) return "NEUTRAL".equals(choice)
                ? "기사에 뚜렷한 방향 신호가 없어 중립을 고른 점은 기사 표현과 맞아요."
                : "기사에 뚜렷한 방향 신호가 없는데 " + label(choice) + "을 골랐어요.";
        boolean aligned = choice.equals("POSITIVE".equals(signal) ? "UP" : "DOWN");
        return "기사의 " + ("POSITIVE".equals(signal) ? "호재" : "악재") + " 신호와 " + label(choice) + " 선택은 " + (aligned ? "같은" : "다른") + " 방향이에요.";
    }

    static String reasonCheck(String userReason, String choice, String verdict, String articleText, String interpretation, String articleSignal, String articleLead) {
        String reason = java.util.Objects.toString(userReason, "").strip();
        String choiceCheck = verdict == null ? choiceCheck(articleSignal,choice) : "";
        String lead = first(articleLead).replaceAll("[.!?]+$", "");
        String example = lead.isBlank() ? "사실 → 이익·수요·금리 → 방향" : "예: “" + lead.substring(0,Math.min(lead.length(),50)) + "” → 이익·수요·금리 변화 → 예상 방향";
        if (reason.isBlank()) return verdict == null
                ? choiceCheck + " 다음엔 판단 근거를 한 줄 남겨보세요(" + example + ")."
                : "다음엔 판단 근거를 한 줄 남겨보세요.";
        String source = articleText.toLowerCase(Locale.ROOT);
        boolean hasFact = REASON_WORDS.matcher(reason).results().map(match -> match.group().substring(0,2).toLowerCase(Locale.ROOT))
                .anyMatch(prefix -> !GENERIC_PREFIXES.contains(prefix) && source.contains(prefix));
        boolean hasLink = LINK_WORDS.matcher(reason).find();
        String reasonSignal = signal(reason,null,null);
        String expected = "POSITIVE".equals(reasonSignal) ? "UP" : "NEGATIVE".equals(reasonSignal) ? "DOWN" : null;
        boolean contradicts = choice != null && expected != null && !expected.equals(choice);
        boolean strong = hasFact && hasLink && !contradicts;
        var gaps = new java.util.ArrayList<String>();
        if (!hasFact) gaps.add("기사에서 확인한 사실");
        if (!hasLink) gaps.add("이익·수요·금리와 가격의 연결");
        if (contradicts) gaps.add("근거의 방향과 " + label(choice) + " 선택 사이의 모순");
        String improve = "다음엔 " + String.join(" · ",gaps) + " 부분을 보완해보세요.";
        String quote = "남긴 근거 “" + reason + "”";
        if ("CORRECT".equals(verdict)) return strong ? quote + "는 기사 속 사실과 가격 경로를 연결해 판단 과정도 탄탄했어요."
                : quote + "로 방향은 맞혔지만 근거가 약해 운이 섞였을 수 있어요. " + improve;
        if ("PARTIAL".equals(verdict)) return quote + "와 예측 방향은 실제 결과와 같았지만 변동이 작아 근거의 힘을 확인하기 어려워요. "
                + (strong ? "기사 사실과 가격 경로를 연결한 점은 유지해보세요." : improve);
        if ("WRONG".equals(verdict)) return strong ? quote + "는 기사 속 사실과 가격 경로를 연결한 타당한 추론이었어요. " + interpretation
                + " 좋은 근거도 다른 요인과 함께 작용해 결과는 빗나갈 수 있어요."
                : quote + "에서 기사 사실과 가격 경로를 충분히 잇지 못했어요. " + improve;
        return choiceCheck + " " + (strong ? quote + "는 기사 표현과 가격 경로를 연결했지만 실제 결과가 없어 적중 여부는 아직 알 수 없어요."
                : quote + "에는 보완할 부분이 있어요. " + improve);
    }

    static List<AiFeedbackResponse.Reason> articleReview(com.finsight.news.News news, String userReason) {
        return articleReview(news,userReason,null);
    }
    static List<AiFeedbackResponse.Reason> articleReview(com.finsight.news.News news, String userReason, JudgementChoice choice) {
        String core = first(new com.finsight.news.collect.ArticleContentExtractor().clean(news.getTitle(),news.getRawContent()));
        String text = news.getTitle() + " " + core;
        String signal = signal(text,java.util.Objects.toString(news.getSentimentHint(),null),news.getSentimentConfidence());
        String explanation = core.isBlank() ? "기사 본문이 없어 가격에 영향을 줄 근거를 확인하기 어려워요."
                : "기사에서는 “" + core + "”라고 전했어요. " + priceChannel(text,signal);
        explanation += core.isBlank() ? " 본문이 없어 기사 신호도 확인하기 어려워요."
                : "POSITIVE".equals(signal) ? " 기사 표현은 호재 신호로 읽히지만 실제 가격 방향을 보장하지는 않아요."
                : "NEGATIVE".equals(signal) ? " 기사 표현은 악재 신호로 읽히지만 실제 가격 방향을 보장하지는 않아요."
                : " 이 기사에는 뚜렷한 호재·악재 방향 신호가 없어 중립으로 읽혀요.";
        return List.of(new AiFeedbackResponse.Reason("기사의 핵심과 의미",explanation),
                new AiFeedbackResponse.Reason("내 판단 돌아보기",reasonCheck(userReason,choice == null ? null : choice.name(),null,text,"",signal,core)));
    }
    public static AiFeedbackResponse generate(AiFeedbackRequest request) {
        boolean aligned = request.userChoice().equals(request.marketResult());
        double percent = request.actualChangePercent();
        String verdict = aligned && Math.abs(percent) <= 1.0 + 1e-9 ? "PARTIAL" : aligned ? "CORRECT" : "WRONG";
        String reason = first(request.userReason());
        String headline = "PARTIAL".equals(verdict) ? "방향은 맞았지만 변동이 작았어요" : "WRONG".equals(verdict)
                ? "다르게 움직였어요" : "예측 방향이 같았어요";
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
        String interpretation = interpretation(signal,request.marketResult(),percent,hasIndex ? request.indexChangePercent() : null);
        String article = core.isBlank() ? "저장된 기사 근거가 없어 뉴스와 가격의 관계를 확인하기 어려워요. " + interpretation
                : "기사에서는 “" + core + "”라고 전했어요. " + priceChannel(request.newsTitle() + " " + core, signal) + " " + interpretation;
        String tip = indexTarget || followsMarket ? "판단 전 그날 " + (indexTarget ? request.relatedSymbolName() : request.indexName()) + " 흐름을 함께 보세요."
                : (request.newsTitle() + " " + core).matches("(?s).*(실적|매출|영업이익|호실적).*" ) ? "실적 발표 일정과 시장 예상치를 확인하세요."
                : (request.newsTitle() + " " + core).matches("(?s).*(매수|매도|수급|외국인).*" ) ? "외국인 매매와 거래량이 함께 달라졌는지 확인하세요."
                : "개별 소식의 발표 시점과 같은 날 시장 흐름을 나란히 비교해보세요.";
        return new AiFeedbackResponse(verdict, headline, summary, List.of(
                new AiFeedbackResponse.Reason("기사 신호와 결과", article),
                new AiFeedbackResponse.Reason(reason.isBlank() ? "근거 남기기" : "내 근거 점검", reasonCheck(request.userReason(),request.userChoice(),verdict,request.newsTitle() + " " + core,interpretation,signal,core)),
                new AiFeedbackResponse.Reason("시장 비교", market)), tip, request.conceptTerm(), request.conceptDefinition(), "RULE");
    }
    static String signal(String text, String hint, Double confidence) {
        if (text.matches("(?s).*(금리|수익률) ?(상승|급등|인상).*" ) && !text.matches("(?s).*(금리|수익률) ?(하락|인하).*")) return "NEGATIVE";
        if (text.matches("(?s).*(금리|수익률) ?(하락|급락|인하).*" ) && !text.matches("(?s).*(금리|수익률) ?(상승|인상).*")) return "POSITIVE";
        if (text.contains("환율") && !text.matches("(?s).*(수출|수입).*")) return "NEUTRAL";
        boolean positive = text.matches("(?s).*(호재|급등|상향|호실적|수혜|증가|개선|흑자|반등|상승|급증|치솟).*" );
        boolean negative = text.matches("(?s).*(악재|급락|하향|부진|규제 강화|감소|악화|적자|하락|약세|우려).*" );
        if (positive != negative) return positive ? "POSITIVE" : "NEGATIVE";
        if (!positive && confidence != null && confidence >= 0.6 && hint != null) return hint;
        return "NEUTRAL";
    }
    static String priceChannel(String text, String signal) {
        if (text.contains("금리")) return "금리가 오르면 기업이 돈을 빌리는 비용이 커져 이익 기대에 부담이 될 수 있고, 내려가면 그 부담이 줄 수 있어요.";
        if (text.contains("환율")) return "환율 변화는 수출로 받는 돈과 수입에 드는 비용에 다르게 작용하므로, 기업의 사업 구조에 따라 주가에 미치는 방향도 달라질 수 있어요.";
        if (text.matches("(?s).*(실적|매출|영업이익|순이익|흑자|적자).*")) return "POSITIVE".equals(signal)
                ? "실적 개선 소식은 앞으로 벌 이익에 대한 기대를 높여 주식을 사려는 수요와 상승 요인으로 연결될 수 있어요."
                : "NEGATIVE".equals(signal) ? "실적 부진 소식은 앞으로 벌 이익에 대한 기대를 낮춰 주식을 팔려는 수요와 하락 요인으로 연결될 수 있어요."
                : "실적 뉴스는 매출뿐 아니라 비용을 뺀 이익이 어떻게 달라지는지에 따라 주가 기대에 다르게 작용할 수 있어요.";
        if (text.matches("(?s).*(매수|매도|수급|팔자|사자).*")) return "매수는 주식을 사려는 수요, 매도는 팔려는 공급이어서 매수 압력이 커지면 상승, 매도 압력이 커지면 하락 요인이 될 수 있어요.";
        if (text.matches("(?s).*(규제|과징금|소송).*")) return "규제나 법적 부담은 사업 범위와 비용을 바꿀 수 있어 앞으로 벌 이익에 대한 기대에도 영향을 줄 수 있어요.";
        if (text.matches("(?s).*(수주|판매 계약).*")) return "수주나 판매 계약은 앞으로의 매출에 대한 기대를 높여 매수 요인이 될 수 있지만, 실제 이익은 비용과 계약 이행에 따라 달라져요.";
        if (text.matches("(?s).*(투자 확대|신규 투자|투자한다|투입|증설).*")) return "투자 확대에는 성장 기대와 당장의 비용 부담이 함께 있어 규모만으로 상승·하락을 단정하기 어려워요.";
        return "이 기사만으로 실제 주가 움직임의 이유를 특정할 실적·수급 근거는 충분하지 않아요.";
    }
    static String first(String value) { return value == null ? "" : value.trim().split("(?<=[.!?])\\s+|\\n+", 2)[0]; }
    private static String label(String value) { return switch (value) { case "UP" -> "상승"; case "DOWN" -> "하락"; default -> "중립"; }; }
}
