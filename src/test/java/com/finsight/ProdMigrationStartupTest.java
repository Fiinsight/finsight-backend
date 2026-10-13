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
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
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

    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.finsight.auth.UserRepository users;
    @Autowired com.finsight.auth.JwtService jwt;
    @Autowired com.finsight.judgement.JudgementRepository judgementRows;
    @Autowired Flyway flyway;
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.finsight.learning.TermMasteryService mastery;
    @Autowired com.finsight.learning.LearningRecommendationService recommendations;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.finsight.external.AiServiceClient ai;

    @Test
    @org.springframework.transaction.annotation.Transactional
    void mixedLegacyRowsKeepHistoryPageAndDetailAvailable() throws Exception {
        Long owner=jdbc.queryForObject("INSERT INTO app_user(email,provider,created_at) VALUES('legacy-ci@test','LOCAL',now()) RETURNING id",Long.class);
        Long news=jdbc.queryForObject("INSERT INTO news(title,url,created_at) VALUES('원문 사실','https://example.test/legacy-ci',now()) RETURNING id",Long.class);
        Long legacy=null;
        for(String payload : java.util.Arrays.asList("[\"이전 근거\",\"두번째 근거\"]","{\"verdict\":\"WRONG\",\"reasons\":[]}","42","\"깨진 형식\"",null)) {
            Long id=jdbc.queryForObject("INSERT INTO judgement(user_id,news_id,choice,created_at,feedback_reasons) VALUES(?,?,'UP',now(),?::jsonb) RETURNING id",Long.class,owner,news,payload);
            if(legacy==null) legacy=id;
        }
        String token=jwt.issue(users.findById(owner).orElseThrow());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/judgements/history/page").header("Authorization","Bearer "+token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.totalElements").value(5))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content.length()").value(5));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/judgements/history").header("Authorization","Bearer "+token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(5));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/judgements/"+legacy).header("Authorization","Bearer "+token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.reasons[0].title").value("근거"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.reasons[0].detail").value("이전 근거"));
        var row=judgementRows.findById(legacy).orElseThrow();
        var content=new com.finsight.judgement.Judgement.FeedbackContent("CORRECT",java.util.List.of(new com.finsight.external.AiFeedbackResponse.Reason("기사","원문 사실")),"비교 기준 확인");
        assertThat(judgementRows.finalizeOutcome(legacy,row.getCreatedAt(),"UP",1.2,"결과",java.time.Instant.now(),java.time.LocalDate.parse("2026-10-07"),java.math.BigDecimal.valueOf(100),java.math.BigDecimal.valueOf(101.2),"결과",com.finsight.judgement.Judgement.feedbackJson(content))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(feedback_reasons) FROM judgement WHERE id=?",String.class,legacy)).isEqualTo("object");
    }

    @Test
    void emptyPostgresMigratesAndProdHibernateValidates() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(Arrays.stream(flyway.info().applied()).map(info -> info.getVersion().toString()))
                .containsExactly("1", "2", "3", "4", "4.1", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15");
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
    void topicsArePreservedButExcludedAndLatestWrongRemainsReviewable() {
        Long user=jdbc.queryForObject("INSERT INTO app_user(email,provider,created_at) VALUES('concept-ci@test','LOCAL',now()) RETURNING id",Long.class);
        Long news=jdbc.queryForObject("INSERT INTO news(title,url,created_at) VALUES('금리와 반도체','https://example.test/concept-ci',now()) RETURNING id",Long.class);
        jdbc.update("INSERT INTO term_mastery(user_id,term,p_known,attempts,last_answered_at) VALUES(?,'반도체',0.99,8,now())",user);
        mastery.answer(user,"반도체","beginner",true);
        assertThat(jdbc.queryForObject("SELECT attempts FROM term_mastery WHERE user_id=? AND term='반도체'",Integer.class,user)).isEqualTo(8);
        assertThat(mastery.list(user)).isEmpty();
        mastery.answer(user,"코스피","analyst",true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM term_mastery WHERE user_id=? AND term='코스피'",Long.class,user)).isZero();
        mastery.answer(user,"금리","beginner",true);
        jdbc.update("INSERT INTO learning_progress(user_id,news_id,term,level,correct,answered_at) VALUES(?,?,'금리','beginner',false,now())",user,news);
        var row=mastery.list(user).getFirst();
        assertThat(row.lastCorrect()).isFalse();
        assertThat(com.finsight.learning.TermMasteryService.overview(mastery.list(user)).weakest()).extracting(com.finsight.learning.TermMasteryService.Mastery::term).containsExactly("금리");
        assertThat(jdbc.queryForObject("SELECT kind FROM term WHERE term='반도체'",String.class)).isEqualTo("TOPIC");
        assertThat(jdbc.queryForObject("SELECT kind FROM term WHERE term='코스피'",String.class)).isEqualTo("MARKET");
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
        assertThat(response.items().getFirst().reason()).isEqualTo("'환율' 퀴즈를 틀린 기사와 비슷해요");
        jdbc.update("DELETE FROM learning_progress WHERE user_id=?",user);
        assertThat(recommendations.get(user,java.util.List.of(source)).items().getFirst().reason()).isEqualTo("메모한 '환율 변화'와 비슷해요");
        jdbc.update("DELETE FROM article_note WHERE user_id=?",user);
        assertThat(recommendations.get(user,java.util.List.of(source)).items().getFirst().reason()).isEqualTo("판단한 '환율 변화'와 비슷해요");
    }
}
