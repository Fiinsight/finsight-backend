package com.finsight.external;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ReadingAidCacheKeyTest {
    @Test void longBodiesNeverRemainInTheKeyAndAllRequestDifferencesStillMatter() {
        String body="본문".repeat(10000);
        String key=AiServiceClient.readingAidCacheKey(new AiRewriteRequest("제목",body,"normal"));
        assertThat(key).hasSize(64).doesNotContain("본문");
        assertThat(AiServiceClient.readingAidCacheKey(new AiRewriteRequest("제목",body,"normal"))).isEqualTo(key);
        assertThat(AiServiceClient.readingAidCacheKey(new AiRewriteRequest("제목",body,"analyst"))).isNotEqualTo(key);
        assertThat(AiServiceClient.readingAidCacheKey(new AiRewriteRequest("제목",body+"수정","normal"))).isNotEqualTo(key);
    }
}
