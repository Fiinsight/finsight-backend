package com.finsight.news.collect;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
class RequestedQualityTest {
 @ParameterizedTest @CsvSource(delimiter='|', value={
  "[마켓뷰] '7천피' 내준 코스피…|KOSPI",
  "코스피 0.27%↓|KOSPI",
  "코스피 1.95%·코스닥 4.48%↑|KOSPI",
  "코스닥 4.48%·코스피 1.95%↑|KOSPI",
  "환율 1350원대|NULL",
  "DS증권 현대로템 목표가↓|064350"
 }) void headlinePriority(String title, String expected) {
  assertThat(new NewsSymbolMatcher().match(title, "SK하이닉스 코스닥 시장을 언급했다. SK하이닉스 전망도 언급했다."))
    .isEqualTo(expected.equals("NULL") ? null : expected);
 }
 @Test void kakaoTalkPublicServiceAndCompanyPrefixesAreNotCompanies() {
  var matcher = new NewsSymbolMatcher();
  assertThat(matcher.match("고용노동부 고객센터, AI 실업급여 상담 서비스 24시간 제공", "카카오톡에서 '고용노동부 고객상담센터' 채널을 검색해 상담하세요. 카카오톡 서비스를 안내합니다.")).isNull();
  assertThat(matcher.match("상담 서비스 안내", "카카오톡에서 안내합니다. 카카오톡 서비스를 제공합니다.")).isNull();
  assertThat(matcher.match("정부 상담 안내", "카카오 상담을 안내합니다.")).isNull();
  assertThat(matcher.match("카카오 실적 전망", "서비스를 안내합니다.")).isEqualTo("035720");
  assertThat(matcher.match("카카오뱅크 실적 전망")).isEqualTo("323410");
  assertThat(matcher.match("카카오페이 실적 전망")).isNull();
 }
 @Test void headlineTargetWinsOverBodyCodesAndCatalogSupportsSearch() {
  var matcher = new NewsSymbolMatcher();
  assertThat(matcher.match("코스피 전망", "현대로템 [064350] 목표가")).isEqualTo("KOSPI");
  assertThat(matcher.match("코스피·코스닥, 삼전닉스 약세에 급락…외국인 팔자", "SK하이닉스[000660]와 삼성전자[005930]가 하락했다.")).isEqualTo("KOSPI");
  assertThat(matcher.match("삼성전자 실적 발표", "SK하이닉스[000660]와 비교했다.")).isEqualTo("005930");
  assertThat(matcher.match("현대로템[064350] 목표가 하향", "시장 코스피 흐름")).isEqualTo("064350");
  assertThat(matcher.match("기업의 신규 계약 발표", "현대로템[064350] 계약을 발표했다.")).isEqualTo("064350");
  assertThat(new NewsSymbolMatcher().match("에코프로비엠 실적 전망")).isEqualTo("247540");
  var service = new com.finsight.stock.PopularStockService(null);
  assertThat(service.search("현대")).hasSizeGreaterThanOrEqualTo(3);
  assertThat(service.search("064350").getFirst().name()).isEqualTo("현대로템");
  assertThat(service.search("123456").getFirst().name()).isEqualTo("123456 차트 보기");
 }
 @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"AFP연합뉴스 ", "2026.7.24 ", "2026.10.12 [공동취재] (서울=연합뉴스) 한지훈 채새롬 기자 = "})
 void creditPrefixes(String prefix) { assertThat(new ArticleContentExtractor().clean("제목", prefix + "반도체 수출이 12.5% 증가했다.")).isEqualTo("반도체 수출이 12.5% 증가했다."); }
 @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"세종시장 집무실 복귀", "속초 고용 지표", "군산 새만금 수산식품단지", "농식품부 K-푸드 수출탑", "산업재산권 분쟁조정위"})
 void localAdministrationIsPenalized(String title) { assertThat(new NewsRelevanceScorer().scoreTitle(title)).isLessThan(0); }
}
