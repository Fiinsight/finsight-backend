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

    static String reasonDirection(String reason) {
        String text = reason.replaceAll("(부담|비용|원가|우려|매도세)[가-힣]{0,3}\\s*(줄어[가-힣]*|감소|완화|낮아[가-힣]*)|줄어드는\\s*(부담|비용)|비용\\s*감소", " 호재 ")
                .replaceAll("(비용|원가|부담|우려|매도세)[가-힣]{0,3}\\s*(늘어[가-힣]*|증가|커지[가-힣]*|확대)|비용\\s*증가", " 악재 ");
        boolean positive = text.matches("(?s).*(커지|커질|늘어|늘|확대|증가|개선|기대|매수세|수혜|호재|반등|회복|상승|오를|올라|흑자).*" );
        boolean negative = text.matches("(?s).*(줄어|감소|악화|부담|우려|매도세|약세|악재|하락|내릴|부진|적자).*" );
        return positive && !negative ? "UP" : negative && !positive ? "DOWN" : "NEUTRAL";
    }
    record ReasonQuality(boolean hasFact, boolean hasLink, boolean contradicts, String expected, boolean substantial) {
        boolean strong() { return hasFact && hasLink && substantial && !contradicts; }
    }
    static ReasonQuality reasonQuality(String reason, String choice, String articleText) {
        var words = REASON_WORDS.matcher(reason).results().map(java.util.regex.MatchResult::group).toList();
        String source = articleText.toLowerCase(Locale.ROOT);
        var facts = words.stream().map(word -> word.substring(0,2).toLowerCase(Locale.ROOT))
                .filter(prefix -> !GENERIC_PREFIXES.contains(prefix) && source.contains(prefix)).collect(java.util.stream.Collectors.toSet());
        boolean hasLink = LINK_WORDS.matcher(reason).results().map(java.util.regex.MatchResult::group)
                .anyMatch(word -> !facts.contains(word.substring(0,2).toLowerCase(Locale.ROOT)));
        String expected = reasonDirection(reason);
        boolean contradicts = choice != null && !"NEUTRAL".equals(expected) && !expected.equals(choice);
        return new ReasonQuality(!facts.isEmpty(),hasLink,contradicts,expected,words.size() > 1 && reason.replaceAll("[^가-힣A-Za-z0-9]", "").length() >= 8);
    }
    static String missCause(String signal, String choice, String direction, Double percent, Double indexChange) {
        if (indexChange != null && Math.abs(indexChange) >= 1 && direction.equals(indexChange > 0 ? "UP" : "DOWN") && !direction.equals(choice)) return "MARKET";
        String expected = "POSITIVE".equals(signal) ? "UP" : "NEGATIVE".equals(signal) ? "DOWN" : null;
        if (expected != null && !expected.equals(choice)) return "CHOICE";
        if (expected != null && !expected.equals(direction)) return "PRICED_IN";
        return "NO_SIGNAL";
    }
    static String interpretation(String signal, String direction, Double percent, Double indexChange, String choice, String verdict) {
        if ("UNKNOWN".equals(direction)) return "실제 가격 결과가 없어 기사 신호가 반영됐는지는 아직 확인할 수 없어요.";
        boolean individual = percent != null && indexChange != null && "INDIVIDUAL".equals(marketComparison(percent,indexChange));
        boolean sameMarket = indexChange != null && direction.equals(direction(indexChange));
        if ("PARTIAL".equals(verdict)) return "등락이 ±1% 이내라 일상적인 가격 변동과 구분하기 어려워 기사 신호의 효과를 확인하기 어려워요.";
        if ("WRONG".equals(verdict)) {
            String cause = missCause(signal,choice,direction,percent,indexChange);
            if ("MARKET".equals(cause)) return "POSITIVE".equals(signal) && "DOWN".equals(direction)
                    ? "긍정적인 호재 신호가 있었지만 시장 전체 하락에 묻혔을 수 있어요."
                    : "시장 전체도 실제 결과와 같은 방향으로 1% 이상 크게 움직여 예상과 반대인 시장 영향이 있었을 수 있어요.";
            if ("CHOICE".equals(cause)) return "기사의 " + ("POSITIVE".equals(signal) ? "호재" : "악재") + " 신호와 다른 방향을 골라 기사 신호와 선택을 연결하는 단계에서 빗나갔을 수 있어요.";
            if ("PRICED_IN".equals(cause)) {
                if ("NEUTRAL".equals(direction)) return "기사의 기대나 우려가 이미 가격에 선반영돼 실제로는 중립 범위에 머물렀을 수 있어요.";
                return "POSITIVE".equals(signal) ? "호재가 이미 가격에 선반영됐거나 다른 악재가 함께 작용해 실제로는 하락했을 수 있어요."
                        : "악재가 이미 가격에 선반영됐거나 우려보다 덜 나쁘다는 안도감이 상승과 함께 작용했을 수 있어요.";
            }
            return individual ? "기사 표현은 중립이지만 시장보다 크게 움직여 다른 개별 요인이 있었을 수 있어요."
                    : indexChange != null ? "기사에 뚜렷한 방향 신호가 없어 시장 흐름이나 수급 등 기사 밖의 요인을 놓쳤을 수 있어요."
                    : "기사에 뚜렷한 방향 신호가 없어 비교 지수도 없는 상태에서는 빗나간 이유를 특정하기 어려워요.";
        }
        String explanation;
        if (percent != null && Math.abs(percent) <= 0.5) explanation = "변동이 작아 시장이 기사 신호를 크게 반영하지 않았을 수 있어요.";
        else if ("POSITIVE".equals(signal)) explanation = "UP".equals(direction) ? "기사의 호재 신호대로 실제 상승 방향도 같았어요."
                : indexChange != null && indexChange < -0.5 ? "긍정적인 호재 신호가 있었지만 시장 전체 하락에 묻혔을 수 있어요."
                : "호재가 이미 가격에 선반영됐거나 다른 악재가 함께 작용해 실제로는 하락했을 수 있어요.";
        else if ("NEGATIVE".equals(signal)) explanation = "DOWN".equals(direction) ? "기사의 악재 신호대로 실제 하락 방향도 같았어요."
                : "악재가 이미 가격에 선반영됐거나 우려보다 덜 나쁘다는 안도감이 상승과 함께 작용했을 수 있어요.";
        else explanation = individual ? "기사 표현은 중립이지만 시장보다 크게 움직여 다른 개별 요인이 있었을 수 있어요."
                : indexChange != null ? "기사에 뚜렷한 방향 신호가 없어 시장 흐름이나 수급을 따라 움직였을 수 있어요."
                : "기사에 뚜렷한 방향 신호가 없어 비교 지수도 없는 상태에서는 개별 요인과 시장 영향을 나누기 어려워요.";
        if ("CORRECT".equals(verdict) && sameMarket && !individual) explanation += " 시장 전체도 같은 방향이라 뉴스만의 효과로 보긴 어려워요.";
        return explanation;
    }
    private static String choiceCheck(String signal, String choice) {
        if (choice == null) return "선택한 방향 정보가 없어 기사 신호와의 일치 여부는 확인하기 어려워요.";
        if ("NEUTRAL".equals(signal)) return "NEUTRAL".equals(choice)
                ? "기사에 뚜렷한 방향 신호가 없어 중립을 고른 점은 기사 표현과 맞아요."
                : "기사에 뚜렷한 방향 신호가 없는데 " + label(choice) + "을 골랐어요.";
        boolean aligned = choice.equals("POSITIVE".equals(signal) ? "UP" : "DOWN");
        return "기사의 " + ("POSITIVE".equals(signal) ? "호재" : "악재") + " 신호와 " + label(choice) + " 선택은 " + (aligned ? "같은" : "다른") + " 방향이에요.";
    }
    static String examplePhrase(String lead) {
        String text = first(lead).replaceAll("[.!?]+$", "").replaceAll("(하기로\\s*했다|되었다|됐다|된다|했다|한다|있다|없다).*$", "").strip();
        String[] words = text.split("\\s+");
        String phrase = "";
        for (int i=words.length-1; i>=0; i--) {
            String word = words[i].replaceAll("(?<=[가-힣]{2})(에서|으로|은|는|이|가|을|를)$", "");
            String candidate = (word + " " + phrase).strip();
            if (candidate.length() > 25) break;
            phrase = candidate;
        }
        return phrase;
    }
    private static String improve(ReasonQuality quality, String choice) {
        if (quality.contradicts()) return "근거는 " + label(quality.expected()) + " 쪽 이야기인데 " + label(choice) + "을 골랐어요.";
        if (!quality.hasFact() && !quality.hasLink()) return "다음엔 기사에서 본 사실과, 그 사실이 이익·수요·금리 중 무엇을 바꾸는지를 함께 적어보세요.";
        if (!quality.hasFact()) return "다음엔 기사에서 확인한 사실을 먼저 적고 예상과 구분해보세요.";
        if (!quality.hasLink()) return "다음엔 그 사실이 이익·수요·금리 중 무엇을 바꾸는지 가격 경로를 연결해보세요.";
        return "한 단어 대신 기사 사실과 가격 경로를 한 문장으로 풀어보세요.";
    }
    private static String neutralCheck(String choice, String direction, Double percent) {
        if (percent == null || direction == null || "UNKNOWN".equals(direction) || (!"NEUTRAL".equals(choice) && !"NEUTRAL".equals(direction)) || direction.equals(choice)) return "";
        return "NEUTRAL".equals(choice) ? "중립은 다음 거래일 등락이 ±0.5% 안일 때 맞아요. 이번엔 " + signed(percent) + "라 범위를 벗어났어요."
                : "이번엔 " + signed(percent) + "로 중립 범위인 ±0.5% 안이라 상승·하락 선택과 달랐어요.";
    }
    static String reasonCheck(String userReason, String choice, String verdict, String articleText, String articleSignal,
                              String articleLead, String direction, Double percent, String cause) {
        String reason = java.util.Objects.toString(userReason, "").strip();
        String choiceCheck = verdict == null ? choiceCheck(articleSignal,choice) : "";
        String neutral = neutralCheck(choice,direction,percent);
        if (reason.isBlank()) {
            String phrase = examplePhrase(articleLead);
            String path = phrase.matches("(?s).*(유가|원가|조달).*" ) && phrase.matches("(?s).*(안정|완화).*" ) ? "원가 부담 완화 → 상승" : "이익·수요·금리 변화 → 예상 방향";
            String example = phrase.isBlank() ? "사실 → 이익·수요·금리 → 방향" : "예: “" + phrase + "” → " + path;
            String detail = verdict == null ? choiceCheck + " 다음엔 판단 근거를 한 줄 남겨보세요(" + example + ")." : "다음엔 판단 근거를 한 줄 남겨보세요.";
            return (detail + " " + neutral).strip();
        }
        var quality = reasonQuality(reason,choice,articleText);
        String quote = "남긴 근거: “" + reason + "”.";
        String assessment;
        if ("CORRECT".equals(verdict)) assessment = quality.strong() ? "기사 속 사실과 가격 경로를 연결해 판단 과정도 탄탄했어요." : "방향은 맞혔지만 근거가 약해 운이 섞였을 수 있어요.";
        else if ("PARTIAL".equals(verdict)) assessment = "방향은 맞았지만 변동이 작아 근거의 힘을 확인하기 어려워요.";
        else if ("WRONG".equals(verdict)) {
            String reference = switch (cause) { case "MARKET" -> "시장 전체의 반대 움직임"; case "CHOICE" -> "기사 신호와 다른 방향 선택"; case "PRICED_IN" -> "기대나 우려의 선반영"; default -> "기사 밖의 요인"; };
            assessment = quality.strong() ? "근거는 타당했지만 " + reference + "으로 빗나갔을 수 있어 좋은 근거도 틀릴 수 있어요." : "기사 사실과 가격 경로를 충분히 잇지 못했어요.";
        } else assessment = quality.strong() ? "기사 표현과 가격 경로를 연결했지만 실제 결과가 없어 적중 여부는 아직 알 수 없어요." : improve(quality,choice);
        if (!neutral.isBlank()) {
            if ("NEUTRAL".equals(choice)) return quote + " " + neutral;
            assessment = quality.strong() ? "근거는 기사 사실과 가격 경로를 이었지만 다음 거래일에는 시장이 이 소식을 크게 반영하지 않았어요." : improve(quality,choice);
            return quote + " " + assessment + " " + neutral;
        }
        String extra = !quality.strong() && verdict != null ? improve(quality,choice) : "";
        return java.util.stream.Stream.of(choiceCheck,quote,assessment,extra).filter(part -> !part.isBlank()).collect(java.util.stream.Collectors.joining(" "));
    }
    private static String nextTip(String reason, String choice, String text, boolean indexTarget, boolean followsMarket, String name) {
        if (reason.isBlank()) return "다음엔 판단 근거를 한 줄 남겨보세요.";
        var quality = reasonQuality(reason,choice,text);
        if (!quality.hasFact()) return "기사에서 확인한 사실을 먼저 적고 예상과 구분해보세요.";
        if (!quality.hasLink()) return "그 사실이 이익·수요·금리 중 무엇을 바꾸는지 한 문장으로 연결해보세요.";
        if (quality.contradicts()) return "근거가 가리키는 상승·하락 방향과 고른 방향을 다시 맞춰보세요.";
        if (!quality.substantial()) return "그 사실이 이익·수요·금리 중 무엇을 바꾸는지 한 문장으로 연결해보세요.";
        if (indexTarget || followsMarket) return "판단 전 그날 " + name + " 흐름을 함께 보세요.";
        if (text.matches("(?s).*(실적|매출|영업이익|호실적).*" )) return "실적 발표 일정과 시장 예상치를 확인하세요.";
        if (text.matches("(?s).*(매수|매도|수급|외국인).*" )) return "외국인 매매와 거래량이 함께 달라졌는지 확인하세요.";
        return "개별 소식의 발표 시점과 같은 날 시장 흐름을 나란히 비교해보세요.";
    }

    static List<AiFeedbackResponse.Reason> articleReview(com.finsight.news.News news, String userReason) {
        return articleReview(news,userReason,null);
    }
    static String articleReviewTip(com.finsight.news.News news, String userReason, JudgementChoice choice) {
        String core = first(new com.finsight.news.collect.ArticleContentExtractor().clean(news.getTitle(),news.getRawContent()));
        return nextTip(java.util.Objects.toString(userReason, "").strip(),choice == null ? null : choice.name(),news.getTitle() + " " + core,false,false,null);
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
                new AiFeedbackResponse.Reason("내 판단 돌아보기",reasonCheck(userReason,choice == null ? null : choice.name(),null,text,signal,core,"UNKNOWN",null,"NO_SIGNAL")));
    }
    public static AiFeedbackResponse generate(AiFeedbackRequest request) {
        boolean aligned = request.userChoice().equals(request.marketResult());
        double percent = request.actualChangePercent();
        String verdict = aligned && Math.abs(percent) <= 1.0 + 1e-9 ? "PARTIAL" : aligned ? "CORRECT" : "WRONG";
        String reason = java.util.Objects.toString(request.userReason(), "").strip();
        String headline = "PARTIAL".equals(verdict) ? "방향은 맞았지만 변동이 작았어요" : "WRONG".equals(verdict)
                ? "다르게 움직였어요" : "예측 방향이 같았어요";
        String result = request.relatedSymbolName() + " " + request.comparisonDate() + " 종가 기준 " + signed(percent) + ".";
        boolean indexTarget = List.of("KOSPI", "KOSDAQ").contains(request.relatedSymbol());
        boolean hasIndex = !indexTarget && request.indexChangePercent() != null;
        boolean followsMarket = hasIndex && "MARKET".equals(marketComparison(percent, request.indexChangePercent()));
        String comparison = indexTarget ? "개별 종목이 아니라 시장 전체 움직임을 비교한 결과예요."
                : !hasIndex ? "같은 구간의 지수 데이터가 없어 시장 영향을 나누어 판단하기 어려워요."
                : followsMarket ? "시장 전체 흐름과 함께 움직였어요."
                : "시장과의 차이가 커 개별 소식 등 다른 요인도 확인해야 해요.";
        String summary = result + " 예측(" + label(request.userChoice()) + ")은 실제 결과(" + label(request.marketResult()) + ")와 "
                + (aligned ? "같은" : "다른") + " 방향이에요. " + comparison;
        if ("PARTIAL".equals(verdict)) summary += " 방향은 맞았지만 변동이 작아 우연일 수 있어요.";
        if (reason.isBlank()) summary += " 다음엔 판단 근거를 한 줄 남겨보세요.";
        summary += " 가격 변화가 이 뉴스 때문에 생겼다고 단정할 수는 없어요.";
        String market = result.substring(0, result.length() - 1);
        if (request.judgementClose() != null && request.comparisonClose() != null)
            market += " (판단 기준 종가 " + request.judgementClose() + " → 결과 종가 " + request.comparisonClose() + ")";
        if (hasIndex) market += ", 같은 비교 구간 " + request.indexName() + " " + signed(request.indexChangePercent());
        if ("analyst".equals(request.level())) market += " (중립 범위는 ±0.5%)";
        market += ". " + comparison;
        if ("beginner".equals(request.level())) market += " 종가는 거래가 끝날 때의 가격이에요.";
        String core = first(request.newsSummary());
        String signal = signal(request.newsTitle() + " " + core, request.sentimentHint(), request.sentimentConfidence());
        String interpretation = interpretation(signal,request.marketResult(),percent,hasIndex ? request.indexChangePercent() : null,request.userChoice(),verdict);
        String article = core.isBlank() ? "저장된 기사 근거가 없어 뉴스와 가격의 관계를 확인하기 어려워요. " + interpretation
                : "기사에서는 “" + core + "”라고 전했어요. " + priceChannel(request.newsTitle() + " " + core, signal) + " " + interpretation;
        String tip = nextTip(reason,request.userChoice(),request.newsTitle() + " " + core,indexTarget,followsMarket,indexTarget ? request.relatedSymbolName() : request.indexName());
        String cause = missCause(signal,request.userChoice(),request.marketResult(),percent,hasIndex ? request.indexChangePercent() : null);
        return new AiFeedbackResponse(verdict, headline, summary, List.of(
                new AiFeedbackResponse.Reason("기사 신호와 결과", article),
                new AiFeedbackResponse.Reason(reason.isBlank() ? "근거 남기기" : "내 근거 점검", reasonCheck(request.userReason(),request.userChoice(),verdict,request.newsTitle() + " " + core,signal,core,request.marketResult(),percent,cause)),
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
