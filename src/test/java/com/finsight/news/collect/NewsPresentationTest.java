package com.finsight.news.collect;

import com.finsight.news.NewsDetailResponse;
import com.rometools.rome.io.SyndFeedInput;
import java.io.StringReader;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NewsPresentationTest {
    @Test void mixedKrxCodeIsNotCardCopy() {
        org.junit.jupiter.api.Assertions.assertEquals("래블업 [market]",com.finsight.news.NewsDetailResponse.cardText("래블업[0147B0] (0147B0) [market]"));
    }
    @Test void cardSummaryHasTwoSentencesWithoutLevelLabelsOrStockCodes() {
        String raw="[사실] 삼성전자[005930]의 매출은 12.5% 늘었다.\n\n[전망] SK하이닉스(000660)의 매출은 3조원이다.\n\n[분석] 세 번째 문단이다.";
        assertThat(NewsDetailResponse.cardSummary(raw)).isEqualTo("삼성전자의 매출은 12.5% 늘었다. SK하이닉스의 매출은 3조원이다.");
        assertThat(NewsDetailResponse.cardSummary("가".repeat(300))).hasSize(150);
        assertThat(NewsDetailResponse.excerpt(raw)).contains("[사실]", "[전망]", "[분석]");
    }
    @Test void marketOverviewsWinOverIncidentalAiIndustryKeywords() {
        var categories=new NewsCategoryClassifier();
        for(String title:java.util.List.of("[마켓뷰] AI 우려 속 코스피 향방", "코스피·코스닥 마감…반도체 약세")) {
            assertThat(categories.classify(title,"AI 실적과 반도체 수출")).isEqualTo("시장");
            assertThat(categories.classify(title)).isEqualTo("시장");
        }
        assertThat(categories.classify("삼성전자 반도체 실적 발표", "코스피도 올랐다.")).isEqualTo("산업·기술");
    }
    @Test void rssMediaAndEnclosuresPreservePublisherUrlsThroughRanking() throws Exception {
        for(String image:java.util.List.of("<media:content url='https://cdn.publisher.test/a.jpg' medium='image'/>","<enclosure url='https://cdn.publisher.test/a.jpg' type='image/jpeg' length='12'/>")) {
            var feed=new SyndFeedInput().build(new StringReader("<rss version='2.0' xmlns:media='http://search.yahoo.com/mrss/'><channel><title>언론사</title><link>https://publisher.test</link><description>뉴스</description><item><title>기사</title><link>https://publisher.test/a</link>"+image+"</item></channel></rss>"));
            String url=RssFeedFetcher.imageUrl(feed.getEntries().getFirst());
            assertThat(url).isEqualTo("https://cdn.publisher.test/a.jpg");
            assertThat(new NewsCandidate("기사","https://publisher.test/a","언론사",Instant.EPOCH,0,url).withScore(10).imageUrl()).isEqualTo(url);
        }
    }
    @Test void ogImageIsTakenFromTheSameHtmlWithoutDownloadingAnImage() {
        String html="<meta property='og:image' content='/images/a.jpg'><article><p>한국 경제의 수출과 반도체 실적이 개선됐다는 내용을 전하는 실제 본문입니다. 기업의 매출과 이익 흐름을 확인할 수 있습니다.</p></article>";
        var result=new ArticleContentExtractor().extractArticleHtml("https://publisher.test/news/a",html).orElseThrow();
        assertThat(result.imageUrl()).isEqualTo("https://publisher.test/images/a.jpg");
        assertThat(result.body()).contains("반도체");
        for(String unsafe:java.util.List.of("data:image/png;base64,abc","file:///tmp/a","http://127.0.0.1/a","http://172.16.0.1/a","https://user:pass@publisher.test/a"))
            assertThat(NewsImageUrl.sanitize(unsafe,"https://publisher.test/a")).isNull();
    }
}
