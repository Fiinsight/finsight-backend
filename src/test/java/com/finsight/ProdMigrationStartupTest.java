package com.finsight;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly opt in against a disposable, loopback-only CI database. */
@EnabledIfEnvironmentVariable(named = "FINSIGHT_MIGRATION_TEST", matches = "true")
@ActiveProfiles("prod")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "finsight.news.collection.enabled=false",
        "finsight.auth.jwt-secret=bWlncmF0aW9uLXRlc3Qtb25seS1zZWNyZXQtYXQtbGVhc3QtMzItYnl0ZXM=",
        "finsight.cors.allowed-origin-patterns=http://localhost:8081"
})
class ProdMigrationStartupTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        int port = Integer.parseInt(System.getenv().getOrDefault("FINSIGHT_MIGRATION_TEST_PORT", "5432"));
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:" + port + "/finsight_migration_ci");
        registry.add("spring.datasource.username", () -> "finsight_migration_ci");
        registry.add("spring.datasource.password", () -> "migration-ci-only");
    }

    @Autowired Flyway flyway;
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.finsight.learning.TermMasteryService mastery;
    @Autowired com.finsight.learning.LearningRecommendationService recommendations;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.finsight.external.AiServiceClient ai;

    @Test
    void emptyPostgresMigratesAndProdHibernateValidates() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(Arrays.stream(flyway.info().applied()).map(info -> info.getVersion().toString()))
                .containsExactly("1", "2", "3", "4", "4.1", "5", "6", "7", "8", "9", "10", "11", "12");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM news", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM judgement", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Long.class)).isZero();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void masteryPersistsOwnerScopedUpdatesAndManualBackfillIsIdempotent() {
        Long user = jdbc.queryForObject("INSERT INTO app_user(email,provider,created_at) VALUES('mastery-ci@test','LOCAL',now()) RETURNING id", Long.class);
        Long news = jdbc.queryForObject("INSERT INTO news(title,url,created_at) VALUES('금리','https://example.test/mastery-ci',now()) RETURNING id", Long.class);
        mastery.answer(user,"금리","beginner",true);
        mastery.answer(user,"금리","beginner",false);
        var row = mastery.list(user).getFirst();
        assertThat(row.attempts()).isEqualTo(2);
        assertThat(row.pKnown()).isCloseTo(com.finsight.learning.KnowledgeTracing.update(com.finsight.learning.KnowledgeTracing.update(.3,true,"beginner"),false,"beginner"), org.assertj.core.data.Offset.offset(1e-12));
        assertThat(mastery.list(-1L)).isEmpty();
        jdbc.update("INSERT INTO learning_progress(user_id,news_id,term,level,correct,answered_at) VALUES(?,?,'환율','normal',true,now())",user,news);
        assertThat(mastery.backfillCount().concepts()).isEqualTo(1);
        assertThat(mastery.backfillMissing()).isEqualTo(1);
        assertThat(mastery.backfillMissing()).isZero();
        assertThat(mastery.list(user).stream().filter(item -> item.term().equals("금리")).findFirst().orElseThrow().pKnown()).isEqualTo(row.pKnown());
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void recentActionWeightsAndExclusionsWorkOnPostgres() {
        Long user = jdbc.queryForObject("INSERT INTO app_user(email,provider,created_at) VALUES('recommendation-ci@test','LOCAL',now()) RETURNING id", Long.class);
        Long source = jdbc.queryForObject("INSERT INTO news(title,url,raw_content,key_terms,published_at,created_at) VALUES('환율 변화','https://example.test/rec-source','원화 환율이 변하면서 수출 기업의 매출 기준을 확인했다.','환율',now(),now()) RETURNING id",Long.class);
        Long candidate = jdbc.queryForObject("INSERT INTO news(title,url,raw_content,key_terms,published_at,created_at) VALUES('수출 기업 원화 매출','https://example.test/rec-candidate','수출 기업의 원화 매출 기준을 확인하는 내용이다.','환율',now(),now()) RETURNING id",Long.class);
        jdbc.update("INSERT INTO learning_progress(user_id,news_id,term,level,correct,answered_at) VALUES(?,?,'환율','beginner',false,now())",user,source);
        jdbc.update("INSERT INTO article_note(user_id,news_id,content,created_at,updated_at) VALUES(?,?,'확인',now(),now())",user,source);
        jdbc.update("INSERT INTO judgement(user_id,news_id,choice,created_at) VALUES(?,?,'UP',now())",user,source);
        org.mockito.Mockito.clearInvocations(ai);
        var response = recommendations.get(user,java.util.List.of(source));
        var sent = org.mockito.ArgumentCaptor.forClass(com.finsight.external.AiRecommendationRequest.class);
        org.mockito.Mockito.verify(ai).recommendations(sent.capture());
        assertThat(sent.getValue().behaviors().getFirst().weight()).isCloseTo(2.7,org.assertj.core.data.Offset.offset(1e-12));
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().newsId()).isEqualTo(candidate);
        assertThat(response.items().getFirst().reasonNewsId()).isEqualTo(source);
    }
}
