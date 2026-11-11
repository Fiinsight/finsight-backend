package com.finsight.learning;
import com.finsight.news.News;
import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.term.Term;
import com.finsight.term.TermRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FactCheckBalanceTest {
    private static final Pattern RATE = Pattern.compile("([-+]?\\d+(?:\\.\\d+)?)%");
    @Test void thirtyArticlesAndOneHundredSeedsDoNotRewardPickingTheSmallestValue() {
        var terms = mock(TermRepository.class);
        var term = new Term("금리", "돈을 빌릴 때 적용되는 이자의 비율");
        when(terms.findAll()).thenReturn(List.of(term));
        var service = new LearningService(null, null, terms, null, null, null, new ArticleContentExtractor(), null, null);
        int[] positions = new int[3];
        for (int articleIndex = 1; articleIndex <= 30; articleIndex++) {
            for (int seed = 0; seed < 100; seed++) {
                News article = mock(News.class);
                when(article.getId()).thenReturn(100_000L + articleIndex * 10_000L + seed);
                when(article.getTitle()).thenReturn("금리 통계 발표");
                when(article.getRawContent()).thenReturn("금리는 " + articleIndex + ".50%로 집계됐다. 금융시장 통계에 해당 수치를 기록했다.");
                var question = service.question(article, term, "analyst");
                assertThat(question.publicQuestion().type()).isEqualTo(QuestionType.FACT_CHECK);
                var ordered = question.publicQuestion().options().stream().sorted(java.util.Comparator.comparing(FactCheckBalanceTest::rate)).toList();
                positions[ordered.indexOf(question.correctAnswer())]++;
                assertThat(service.question(article, term, "analyst")).isEqualTo(question);
            }
        }
        for (int count : positions) assertThat(count / 3000.0).isBetween(.28, .39);
        System.out.printf("FACT_CHECK numeric ranks: min=%d middle=%d max=%d total=3000%n", positions[0], positions[1], positions[2]);
    }

    @Test void boundedSharesStayNonNegativeAndNeverExceedOneHundred() {
        for (String value : List.of("0.1", "0", "99.9", "100")) {
            for (String option : LearningService.factVariants("시장 점유율은 " + value + "%였다."))
                assertThat(rate(option)).isBetween(BigDecimal.ZERO, new BigDecimal("100"));
        }
    }

    @Test void mixedDirectionsAreNotUsedAsOppositeFactDistractors() {
        assertThat(LearningService.factVariants("금리가 상승한 뒤 다시 하락했다.")).isEmpty();
    }

    private static BigDecimal rate(String sentence) {
        var match = RATE.matcher(sentence);
        assertThat(match.find()).as(sentence).isTrue();
        return new BigDecimal(match.group(1));
    }
}
