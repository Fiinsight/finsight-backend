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
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired com.finsight.learning.TermMasteryService mastery;
    @Autowired com.finsight.learning.LearningService learning;
    @Autowired com.finsight.learning.LearningRecommendationService recommendations;
    @Autowired com.finsight.watchlist.WatchlistService watchlist;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.finsight.external.AiServiceClient ai;

    @Test
    @org.springframework.transaction.annotation.Transactional
    void accountDeletionRemovesQuizMasteryAndEveryOwnedTableOnLegacyForeignKey() throws Exception {
        jdbc.execute("ALTER TABLE term_mastery DROP CONSTRAINT term_mastery_user_id_fkey");
        jdbc.execute("ALTER TABLE term_mastery ADD CONSTRAINT term_mastery_user_id_fkey FOREIGN KEY(user_id) REFERENCES app_user(id)");
        Long owner=jdbc.queryForObject("INSERT INTO app_user(email,provider,created_at) VALUES('delete-all-ci@test','LOCAL',now()) RETURNING id",Long.class);
        Long article=jdbc.queryForObject("INSERT INTO news(title,url,raw_content,created_at) VALUES('금리 환율 물가','https://fixture.test/delete-all','금리가 올랐다. 환율과 물가가 변했다.',now()) RETURNING id",Long.class);
        org.springframework.test.util.ReflectionTestUtils.setField(mastery,"dailyCreditEnabled",true);
        try {
            var question=learning.lesson(owner,article,"beginner","금리").question();
            String definition=jdbc.queryForObject("SELECT short_definition FROM term WHERE term='금리'",String.class);
            learning.answer(owner,article,"beginner","금리",question.options().indexOf(definition));
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(mastery,"dailyCreditEnabled",true); }
        jdbc.update("INSERT INTO term_application_mastery(user_id,term,question_type,p_known,attempts,last_answered_at,last_correct) VALUES(?,'금리','DIRECTION',.6,1,now(),true)",owner);
        jdbc.update("INSERT INTO user_watchlist(user_id,symbol,created_at) VALUES(?,'005930',now())",owner);
        jdbc.update("INSERT INTO article_note(user_id,news_id,content,created_at,updated_at) VALUES(?,?,'메모',now(),now())",owner,article);
        jdbc.update("INSERT INTO daily_note(user_id,note_date,content,updated_at) VALUES(?,CURRENT_DATE,'메모',now())",owner);
        jdbc.update("INSERT INTO judgement(user_id,news_id,choice,created_at) VALUES(?,?,'UP',now())",owner,article);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM term_mastery WHERE user_id=?",Integer.class,owner)).isEqualTo(1);
        reading.read(owner,article);
        reading.merge(owner,"delete_device_12345678",java.util.Map.of());
        var registered=com.finsight.auth.AccountDataDeletion.TABLES.stream().map(com.finsight.auth.AccountDataDeletion.OwnedTable::name).collect(java.util.stream.Collectors.toSet());
        var foreignTables=jdbc.queryForList("SELECT DISTINCT c.conrelid::regclass::text FROM pg_constraint c WHERE c.contype='f' AND c.confrelid='app_user'::regclass",String.class);
        assertThat(registered).containsAll(foreignTables);
        entityManager.flush();
        entityManager.clear(); // Separate setup persistence context from the deletion request.
        String token=jwt.issue(users.findById(owner).orElseThrow());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        for(var table:com.finsight.auth.AccountDataDeletion.TABLES) {
            if(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL",Boolean.class,table.name()) == Boolean.TRUE)
                assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table.name()+" WHERE user_id=?",Integer.class,owner)).as(table.name()).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id=?",Integer.class,owner)).isZero();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void firstDailyAnswerAloneUpdatesMasteryInPostgres() throws Exception {
        Long user=jdbc.queryForObject("INSERT INTO app_user(email,provider,created_at) VALUES('daily-credit-ci@test','LOCAL',now()) RETURNING id",Long.class);
        Long article=jdbc.queryForObject("INSERT INTO news(title,url,created_at) VALUES('금리 기사','https://fixture.test/daily-credit',now()) RETURNING id",Long.class);
        org.springframework.test.util.ReflectionTestUtils.setField(mastery,"dailyCreditEnabled",true);
        try {
            assertThat(mastery.answer(user,article,"금리","beginner",false,3,com.finsight.learning.QuestionType.DEFINITION)).isTrue();
            double expected=com.finsight.learning.KnowledgeTracing.update(.3,false,"beginner");
            assertThat(mastery.answer(user,article,"금리","beginner",true,3,com.finsight.learning.QuestionType.DEFINITION)).isFalse();
            assertThat(jdbc.queryForObject("SELECT p_known FROM term_mastery WHERE user_id=? AND term='금리'",Double.class,user)).isEqualTo(expected);
            assertThat(jdbc.queryForObject("SELECT attempts FROM term_mastery WHERE user_id=? AND term='금리'",Integer.class,user)).isEqualTo(1);
            assertThat(mastery.list(user).getFirst().lastCorrect()).isFalse();
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(mastery,"dailyCreditEnabled",true); }
    }

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
        entityManager.flush();
        entityManager.clear(); // Separate setup persistence context from the deletion request.
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
        assertThat(judgementRows.finalizeOutcome(legacy,row.getCreatedAt(),"UP",1.2,"결과",java.time.Instant.now(),java.time.LocalDate.parse("2026-10-07"),java.math.BigDecimal.valueOf(100),java.math.BigDecimal.valueOf(101.2),"결과",com.finsight.judgement.Judgement.feedbackJson(content),"005930")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(feedback_reasons) FROM judgement WHERE id=?",String.class,legacy)).isEqualTo("object");
        assertThat(jdbc.queryForObject("SELECT target_symbol FROM judgement WHERE id=?",String.class,legacy)).isEqualTo("005930");
        assertThat(judgementRows.finalizeOutcome(legacy,row.getCreatedAt(),"DOWN",-2.,"다른 실행",java.time.Instant.now(),java.time.LocalDate.parse("2026-10-07"),java.math.BigDecimal.valueOf(100),java.math.BigDecimal.valueOf(98),"변경",com.finsight.judgement.Judgement.feedbackJson(content),"000660")).isZero();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void publisherImageReferenceRoundTripsWithoutReplacingArticleContent() {
        var article = new com.finsight.news.News("반도체 수출 기사", "https://publisher.test/image-reference", "언론사", java.time.Instant.now(), "반도체 수출이 늘었다.");
        article.setImageUrl("https://cdn.publisher.test/image.jpg");
        entityManager.persist(article); entityManager.flush(); entityManager.clear();
        assertThat(jdbc.queryForObject("SELECT image_url FROM news WHERE id=?", String.class, article.getId())).isEqualTo("https://cdn.publisher.test/image.jpg");
        assertThat(jdbc.queryForObject("SELECT raw_content FROM news WHERE id=?", String.class, article.getId())).isEqualTo("반도체 수출이 늘었다.");
    }

    @Test
    void emptyPostgresMigratesAndProdHibernateValidates() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("finsight.auth.expiration-seconds"))
                .isEqualTo(System.getenv().getOrDefault("JWT_EXPIRATION_SECONDS","604800"));
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(Arrays.stream(flyway.info().applied()).map(info -> info.getVersion().toString()))
                .containsExactly("1", "2", "3", "4", "4.1", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM news", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM judgement", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Long.class)).isZero();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void unknownRegradeIsOwnerScopedConditionalAndRefreshesTheArrivalTimestamp() {
        Long owner=jdbc.queryForObject("INSERT INTO app_user(email,provider,created_at) VALUES('regrade-ci@test','LOCAL',now()) RETURNING id",Long.class);
        Long news=jdbc.queryForObject("INSERT INTO news(title,url,created_at) VALUES('기업 정책','https://example.test/regrade-ci',now()) RETURNING id",Long.class);
        Long id=jdbc.queryForObject("INSERT INTO judgement(user_id,news_id,choice,created_at,actual_direction,feedback_generated_at) VALUES(?,?,'UP','2026-09-28T01:00:00Z','UNKNOWN','2026-10-01T08:00:00Z') RETURNING id",Long.class,owner,news);
        var row=judgementRows.findUnknownByOwner(owner).getFirst();
        var content=new com.finsight.judgement.Judgement.FeedbackContent("CORRECT",java.util.List.of(new com.finsight.external.AiFeedbackResponse.Reason("기사","기업 정책")),"시장 확인");
        var json=com.finsight.judgement.Judgement.feedbackJson(content);
        var generated=java.time.Instant.parse("2026-10-14T08:00:00Z");
        assertThat(judgementRows.findUnknownByOwner(-1L)).isEmpty();
        assertThat(judgementRows.regradeUnknownOutcome(id,-1L,row.getCreatedAt(),"KOSPI","UP",2.,java.time.LocalDate.parse("2026-09-29"),java.math.BigDecimal.valueOf(100),java.math.BigDecimal.valueOf(102),"새 결과","방향 일치",json,generated)).isZero();
        assertThat(judgementRows.regradeUnknownOutcome(id,owner,row.getCreatedAt(),"KOSPI","UP",2.,java.time.LocalDate.parse("2026-09-29"),java.math.BigDecimal.valueOf(100),java.math.BigDecimal.valueOf(102),"새 결과","방향 일치",json,generated)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT target_symbol FROM judgement WHERE id=?",String.class,id)).isEqualTo("KOSPI");
        assertThat(jdbc.queryForObject("SELECT feedback_generated_at FROM judgement WHERE id=?",java.sql.Timestamp.class,id).toInstant()).isEqualTo(generated);
        assertThat(judgementRows.regradeUnknownOutcome(id,owner,row.getCreatedAt(),"KOSPI","DOWN",-2.,java.time.LocalDate.parse("2026-09-29"),java.math.BigDecimal.valueOf(100),java.math.BigDecimal.valueOf(98),"덮어쓰기","변경",json,generated)).isZero();
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
        jdbc.update("INSERT INTO learning_progress(user_id,news_id,term,level,correct,answered_at,question_type) VALUES(?,?,'환율','normal',true,now(),'DEFINITION')",user,news);
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
        mastery.answer(user,news,"금리","beginner",false,3,com.finsight.learning.QuestionType.DEFINITION);
        jdbc.update("INSERT INTO learning_progress(user_id,news_id,term,level,correct,answered_at,question_type) VALUES(?,?,'금리','beginner',false,now(),'DEFINITION')",user,news);
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
        jdbc.update("INSERT INTO learning_progress(user_id,news_id,term,level,correct,answered_at,question_type) VALUES(?,?,'환율','beginner',false,now(),'DEFINITION')",user,source);
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

    @Test
    @org.springframework.transaction.annotation.Transactional
    void watchlistMigrationOwnerScopeLimitAndRecentNewsWorkOnPostgres() throws Exception {
        Long owner=jdbc.queryForObject("INSERT INTO app_user(email,provider,created_at) VALUES('watch-ci@test','LOCAL',now()) RETURNING id",Long.class);
        Long other=jdbc.queryForObject("INSERT INTO app_user(email,provider,created_at) VALUES('watch-other-ci@test','LOCAL',now()) RETURNING id",Long.class);
        String token=jwt.issue(users.findById(owner).orElseThrow()),otherToken=jwt.issue(users.findById(other).orElseThrow());
        var symbols=com.finsight.stock.StockCatalog.entries().keySet().stream().limit(21).toList();
        for(String symbol:symbols.subList(0,20)) mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/watchlist/"+symbol).header("Authorization","Bearer "+token)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/watchlist/"+symbols.getFirst()).header("Authorization","Bearer "+token)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/watchlist/"+symbols.get(20)).header("Authorization","Bearer "+token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("관심종목은 20개까지 추가할 수 있어요"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/watchlist").header("Authorization","Bearer "+token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(20))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].symbol").value(symbols.getFirst()));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/watchlist").param("userId",owner.toString()).header("Authorization","Bearer "+otherToken))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(0));
        for(String invalid:java.util.List.of("KOSPI","KOSDAQ","999999")) mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/watchlist/"+invalid).header("Authorization","Bearer "+token)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        for(int i=0;i<7;i++) jdbc.update("INSERT INTO news(title,url,raw_content,related_symbol,published_at,created_at) VALUES(?,?,'기업 실적을 확인했다.',?,now()-(? * interval '1 hour'),now())","관심 뉴스 "+i,"https://example.test/watch/"+i,symbols.getFirst(),i+1);
        jdbc.update("INSERT INTO news(title,url,related_symbol,published_at,created_at) VALUES('48시간 밖','https://example.test/watch/old',?,now()-interval '49 hours',now())",symbols.getFirst());
        jdbc.update("INSERT INTO news(title,url,related_symbol,published_at,created_at) VALUES('미래 기사','https://example.test/watch/future',?,now()+interval '1 hour',now())",symbols.getFirst());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/news/watchlist").header("Authorization","Bearer "+token))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(5))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].title").value("관심 뉴스 0"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[4].title").value("관심 뉴스 4"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/news/watchlist").header("Authorization","Bearer "+otherToken)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(0));
        for(int i=0;i<2;i++) mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/watchlist/"+symbols.getFirst()).header("Authorization","Bearer "+token)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_watchlist WHERE user_id=?",Integer.class,owner)).isEqualTo(19);
    }

    @Test
    void concurrentWatchlistAddsCannotExceedTwenty() throws Exception {
        Long owner=jdbc.queryForObject("INSERT INTO app_user(email,provider,created_at) VALUES('watch-race-ci@test','LOCAL',now()) RETURNING id",Long.class);
        var symbols=com.finsight.stock.StockCatalog.entries().keySet().stream().limit(21).toList();
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for(String symbol:symbols.subList(0,19)) watchlist.add(owner,symbol);
            var start=new java.util.concurrent.CountDownLatch(1);
            var results=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for(String symbol:symbols.subList(19,21)) results.add(executor.submit(()->{
                start.await();
                try { watchlist.add(owner,symbol);return 200; }
                catch(org.springframework.web.server.ResponseStatusException error) {return error.getStatusCode().value();}
            }));
            start.countDown();
            assertThat(java.util.List.of(results.get(0).get(10,java.util.concurrent.TimeUnit.SECONDS),results.get(1).get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_watchlist WHERE user_id=?",Integer.class,owner)).isEqualTo(20);
        } finally { jdbc.update("DELETE FROM app_user WHERE id=?",owner); }
    }
    @Test
    @org.springframework.transaction.annotation.Transactional
    void conceptUpdatesUseDecayedPriorAndReadingAnswersCannotReplaceDefinitionEvidence() {
        Long user=jdbc.queryForObject("INSERT INTO app_user(email,provider,created_at) VALUES('decay-ci@test','LOCAL',now()) RETURNING id",Long.class);
        Long news=jdbc.queryForObject("INSERT INTO news(title,url,created_at) VALUES('금리 상승','https://example.test/decay',now()) RETURNING id",Long.class);
        jdbc.update("INSERT INTO term_mastery(user_id,term,p_known,attempts,last_answered_at,definition_only) VALUES(?,'금리',0.95,4,now()-interval '60 days',true)",user);
        double prior=mastery.list(user).getFirst().effectivePKnown();
        mastery.answer(user,"금리","analyst",true);
        assertThat(mastery.list(user).getFirst().pKnown()).isCloseTo(com.finsight.learning.KnowledgeTracing.update(prior,true,"beginner"),org.assertj.core.data.Offset.offset(1e-12));
        jdbc.update("INSERT INTO learning_progress(user_id,news_id,term,level,correct,answered_at,question_type) VALUES(?,?,'금리','analyst',false,now()-interval '1 minute','DEFINITION'),(?,?,'금리','analyst',true,now(),'FACT_CHECK')",user,news,user,news);
        org.springframework.test.util.ReflectionTestUtils.setField(mastery,"dailyCreditEnabled",false);
        try {
            assertThat(mastery.list(user).getFirst().lastCorrect()).isFalse();
            assertThat(mastery.list(user).getFirst().level()).isEqualTo("analyst");
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(mastery,"dailyCreditEnabled",true); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_progress WHERE user_id=?",Integer.class,user)).isEqualTo(2);
        jdbc.update("INSERT INTO term_mastery(user_id,term,p_known,attempts,last_answered_at) VALUES(?,'환율',0.99,99,now())",user);
        assertThat(mastery.list(user)).extracting(com.finsight.learning.TermMasteryService.Mastery::term).containsExactly("금리");
    }

    @Test
    void migrationReplaysOnlySavedDefinitionsAndRetainsUnverifiableLegacySnapshots() {
        String schema="learning_type_migration_ci";
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            var source=jdbc.getDataSource();
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("16").load().migrate();
            jdbc.update("INSERT INTO "+schema+".term(term,short_definition,kind) VALUES('금리','이자 비율','CONCEPT'),('환율','통화 교환 비율','CONCEPT')");
            Long user=jdbc.queryForObject("INSERT INTO "+schema+".app_user(email,provider,created_at) VALUES('replay-ci@test','LOCAL',now()) RETURNING id",Long.class);
            for (int id=1;id<=3;id++) jdbc.update("INSERT INTO "+schema+".news(id,title,url,created_at) VALUES(?,?,?,now())",id,"금리 기사","https://example.test/replay/"+id);
            for (var entry : java.util.Map.of(1,"beginner",2,"normal",3,"analyst").entrySet())
                jdbc.update("INSERT INTO "+schema+".learning_progress(user_id,news_id,term,level,correct,answered_at) VALUES(?,?,'금리',?,true,now())",user,entry.getKey(),entry.getValue());
            jdbc.update("INSERT INTO "+schema+".term_mastery(user_id,term,p_known,attempts,last_answered_at) VALUES(?,'금리',0.99,99,now()),(?,'환율',0.99,99,now())",user,user);
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
            assertThat(jdbc.queryForObject("SELECT p_known FROM "+schema+".term_mastery WHERE term='금리'",Double.class)).isCloseTo(0.6080838323353294,org.assertj.core.data.Offset.offset(1e-12)); // V17's historical .33 model remains unchanged.
            assertThat(jdbc.queryForObject("SELECT attempts FROM "+schema+".term_mastery WHERE term='금리'",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT definition_only FROM "+schema+".term_mastery WHERE term='환율'",Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+schema+".learning_progress",Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForList("SELECT question_type FROM "+schema+".learning_progress",String.class)).containsExactlyInAnyOrder("DEFINITION","DIRECTION","FACT_CHECK");
        } finally { jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }

    @Test
    @org.springframework.transaction.annotation.Transactional
    void repeatingTheSameDefinitionsCannotEarnAnalystPromotion() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(mastery,"dailyCreditEnabled",true);
        try {
        Long user=jdbc.queryForObject("INSERT INTO app_user(email,provider,created_at,learning_level) VALUES('promotion-ci@test','LOCAL',now(),'normal') RETURNING id",Long.class);
        var concepts=java.util.List.of("금리","환율","물가","기준금리","국채금리");
        Long news=jdbc.queryForObject("INSERT INTO news(title,url,raw_content,created_at) VALUES('금리 환율 물가 기준금리 국채금리','https://example.test/promotion','금리는 2% 상승했다. 환율은 3% 상승했다. 물가는 1% 상승했다. 기준금리는 4% 상승했다. 국채금리는 5% 상승했다.',now()) RETURNING id",Long.class);
        assertThat(learning.lesson(user,news,"analyst","금리").question().type()).isEqualTo(com.finsight.learning.QuestionType.DEFINITION);
        for (String term : concepts) {
            String definition=jdbc.queryForObject("SELECT short_definition FROM term WHERE term=?",String.class,term);
            for (int attempt=0;attempt<3;attempt++) {
                var question=learning.lesson(user,news,"normal",term).question();
                assertThat(question.type()).isEqualTo(com.finsight.learning.QuestionType.DEFINITION);
                assertThat(learning.answer(user,news,"normal",term,question.options().indexOf(definition)).correct()).isTrue();
            }
        }
        assertThat(mastery.list(user)).hasSize(5);
        assertThat(com.finsight.learning.TermMasteryService.suggestion(mastery.list(user),"normal")).isNull();
        assertThat(mastery.list(user)).allMatch(row -> row.attempts()==1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_progress WHERE user_id=? AND question_type='DEFINITION'",Integer.class,user)).isEqualTo(5);
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(mastery,"dailyCreditEnabled",true); }
    }


    @Test
    @org.springframework.transaction.annotation.Transactional
    void applicationEvidenceIsDailyLockedAndSeparateFromDefinitionAndPromotion() {
        org.springframework.test.util.ReflectionTestUtils.setField(mastery,"dailyCreditEnabled",true);
        Long user=jdbc.queryForObject("INSERT INTO app_user(email,provider,created_at) VALUES('application-ci@test','LOCAL',now()) RETURNING id",Long.class);
        Long article=jdbc.queryForObject("INSERT INTO news(title,url,raw_content,created_at) VALUES('적용 테스트','https://fixture.test/application','금리는 2% 상승했다.',now()) RETURNING id",Long.class);
        assertThat(mastery.answer(user,article,"금리","normal",true,3,com.finsight.learning.QuestionType.DIRECTION)).isTrue();
        assertThat(mastery.answer(user,article,"금리","normal",false,3,com.finsight.learning.QuestionType.DIRECTION)).isFalse();
        assertThat(mastery.applicationProbabilities(user,"normal").get("금리")).isEqualTo(com.finsight.learning.KnowledgeTracing.update(.3,true,"normal"));
        assertThat(mastery.applicationProbabilities(user,"analyst")).isEmpty();
        assertThat(mastery.list(user)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT attempts FROM term_application_mastery WHERE user_id=?",Integer.class,user)).isEqualTo(1);
        Long other=jdbc.queryForObject("INSERT INTO news(title,url,raw_content,created_at) VALUES('사실 테스트','https://fixture.test/fact','금리는 2% 상승했다.',now()) RETURNING id",Long.class);
        assertThat(mastery.answer(user,other,"금리","analyst",false,3,com.finsight.learning.QuestionType.FACT_CHECK)).isTrue();
        assertThat(mastery.applicationProbabilities(user,"analyst").get("금리")).isEqualTo(com.finsight.learning.KnowledgeTracing.update(.3,false,"analyst"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM term_application_mastery WHERE user_id=?",Integer.class,user)).isEqualTo(2);
        assertThat(com.finsight.learning.TermMasteryService.suggestion(mastery.list(user),"normal")).isNull();
    }

    @Autowired com.finsight.learning.LearningProgressService learningProgress;
    @Test @org.springframework.transaction.annotation.Transactional
    void liveDefinitionCreditUpdatesProgressReviewAndPromotionWithoutReplayingHistory() {
        assertThat(environment.getProperty("finsight.learning.daily-credit-enabled",Boolean.class)).isTrue();
        Long user=jdbc.queryForObject("INSERT INTO app_user(email,provider,created_at,learning_level) VALUES('live-credit-ci@test','LOCAL',now(),'beginner') RETURNING id",Long.class);
        Long first=jdbc.queryForObject("INSERT INTO news(title,url,raw_content,created_at) VALUES('금리 환율 물가','https://fixture.test/live-credit-a','금리 환율 물가 영업이익 매출 실적을 확인했다.',now()) RETURNING id",Long.class);
        Long second=jdbc.queryForObject("INSERT INTO news(title,url,raw_content,created_at) VALUES('금리 환율 물가','https://fixture.test/live-credit-b','금리 환율 물가 영업이익 매출 실적을 확인했다.',now()) RETURNING id",Long.class);
        jdbc.update("INSERT INTO learning_progress(user_id,news_id,term,level,correct,answered_at,question_type) VALUES(?,?,'환율','beginner',true,now()-interval '1 day','DEFINITION')",user,first);
        assertThat(mastery.list(user)).isEmpty(); // No legacy replay on read/activation.
        var fixed=java.time.Clock.fixed(java.time.Instant.parse("2026-11-02T15:01:00Z"),java.time.ZoneId.of("Asia/Seoul"));
        org.springframework.test.util.ReflectionTestUtils.setField(mastery,"clock",fixed);
        try {
            var q=learning.lesson(user,first,"beginner","금리").question();
            String definition=jdbc.queryForObject("SELECT short_definition FROM term WHERE term='금리'",String.class);
            int correct=q.options().indexOf(definition);
            assertThat(learning.answer(user,first,"beginner","금리",(correct+1)%3).practice()).isFalse();
            double wrong=com.finsight.learning.KnowledgeTracing.update(.3,false,"beginner");
            assertThat(mastery.list(user).getFirst().pKnown()).isEqualTo(wrong);
            assertThat(learning.reviews(user)).extracting(com.finsight.learning.LearningService.Review::term).contains("금리");
            assertThat(learning.answer(user,first,"beginner","금리",correct).practice()).isTrue();
            assertThat(mastery.list(user).getFirst().pKnown()).isEqualTo(wrong);
            var q2=learning.lesson(user,second,"beginner","금리").question();
            assertThat(learning.answer(user,second,"beginner","금리",q2.options().indexOf(definition)).practice()).isFalse();
            assertThat(mastery.list(user).getFirst().pKnown()).isEqualTo(com.finsight.learning.KnowledgeTracing.update(wrong,true,"beginner"));
            assertThat(jdbc.queryForObject("SELECT min(quiz_day) FROM learning_mastery_credit WHERE user_id=?",java.sql.Date.class,user).toLocalDate()).isEqualTo(java.time.LocalDate.parse("2026-11-03"));
            for(String term:java.util.List.of("금리","환율","물가","매출","영업이익")) for(int i=0;i<4;i++) {
                Long article=jdbc.queryForObject("INSERT INTO news(title,url,created_at) VALUES('개념 퀴즈',?,now()) RETURNING id",Long.class,"https://fixture.test/live-"+term+"-"+i);
                mastery.answer(user,article,term,"beginner",true,3,com.finsight.learning.QuestionType.DEFINITION);
            }
            var result=learningProgress.get(user);
            assertThat(result.mastery().strongest()).hasSize(3); // UI shows the top three.
            assertThat(mastery.list(user).stream().filter(com.finsight.learning.TermMasteryService::wellKnown).count()).isEqualTo(5);
            assertThat(result.levelSuggestion()).isEqualTo("normal");
            assertThat(learning.reviews(user)).extracting(com.finsight.learning.LearningService.Review::term).doesNotContain("금리");
            Long reviewArticle=jdbc.queryForObject("INSERT INTO news(title,url,raw_content,created_at) VALUES('금리 환율 물가','https://fixture.test/live-review','금리 환율 물가를 확인했다.',now()) RETURNING id",Long.class);
            var reviewQuiz=learning.lesson(user,reviewArticle,"beginner","금리").question();
            int right=reviewQuiz.options().indexOf(definition);
            learning.answer(user,reviewArticle,"beginner","금리",(right+1)%3);
            double afterWrong=mastery.list(user).stream().filter(row->row.term().equals("금리")).findFirst().orElseThrow().pKnown();
            assertThat(afterWrong).isGreaterThan(.5);
            learning.answer(user,reviewArticle,"beginner","금리",right);
            assertThat(learning.reviews(user)).extracting(com.finsight.learning.LearningService.Review::term).contains("금리");
            assertThat(learningProgress.get(user).levelSuggestion()).isNull();
            assertThat(mastery.list(user).stream().filter(row->row.term().equals("금리")).findFirst().orElseThrow().pKnown()).isEqualTo(afterWrong);
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(mastery,"clock",java.time.Clock.system(java.time.ZoneId.of("Asia/Seoul"))); }
    }

    @Autowired com.finsight.learning.ReadingService reading;
    @Test @org.springframework.transaction.annotation.Transactional
    void serverReadingIsKstScopedIdempotentOwnerIsolatedAndDeletedWithAccount() throws Exception {
        Long owner=jdbc.queryForObject("INSERT INTO app_user(email,provider,created_at) VALUES('reading-owner-ci@test','LOCAL',now()) RETURNING id",Long.class);
        Long other=jdbc.queryForObject("INSERT INTO app_user(email,provider,created_at) VALUES('reading-other-ci@test','LOCAL',now()) RETURNING id",Long.class);
        var ids=new java.util.ArrayList<Long>();
        for(int i=0;i<3;i++) ids.add(jdbc.queryForObject("INSERT INTO news(title,url,created_at) VALUES('읽을 기사',?,now()) RETURNING id",Long.class,"https://fixture.test/reading/"+i));
        var fixed=java.time.Clock.fixed(java.time.Instant.parse("2026-11-02T15:01:00Z"),java.time.ZoneId.of("Asia/Seoul"));
        org.springframework.test.util.ReflectionTestUtils.setField(reading,"clock",fixed);
        org.springframework.test.util.ReflectionTestUtils.setField(learningProgress,"clock",fixed);
        try {
            for(Long id:ids) reading.read(owner,id);
            reading.read(owner,ids.getFirst());
            assertThat(reading.log(owner).get("2026-11-03")).hasSize(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reading_record WHERE user_id=?",Integer.class,owner)).isEqualTo(3);
            reading.merge(owner,"device_A_12345678",java.util.Map.of("2026-11-02",ids));
            reading.merge(owner,"device_A_12345678",java.util.Map.of("2026-11-01",ids)); // Replayed token cannot import twice.
            reading.merge(owner,"device_B_12345678",java.util.Map.of("2026-11-02",ids));
            assertThat(reading.log(owner)).containsOnlyKeys("2026-11-02","2026-11-03");
            assertThat(reading.log(owner).get("2026-11-02")).hasSize(3);
            assertThat(reading.log(other)).isEmpty();
            assertThat(learningProgress.get(owner).goal().achievedToday()).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(()->reading.merge(owner,"device_C_12345678",java.util.Map.of("2026-11-04",ids))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM reading_import WHERE user_id=?",Integer.class,owner)).isEqualTo(2);
            var token=jwt.issue(users.findById(other).orElseThrow());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/learning/reads").param("userId",owner.toString()).header("Authorization","Bearer "+token)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("{}"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/learning/reads/merge").header("Authorization","Bearer "+token).contentType("application/json").content("{\"deviceId\":\"bad\",\"days\":{}}" )).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            new com.finsight.auth.AccountDataDeletion(jdbc).deleteForUser(owner);
            for(String table:java.util.List.of("reading_record","reading_attendance","reading_import")) assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE user_id=?",Integer.class,owner)).isZero();
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(reading,"clock",java.time.Clock.system(java.time.ZoneId.of("Asia/Seoul")));
            org.springframework.test.util.ReflectionTestUtils.setField(learningProgress,"clock",java.time.Clock.system(java.time.ZoneId.of("Asia/Seoul")));
        }
    }
}
