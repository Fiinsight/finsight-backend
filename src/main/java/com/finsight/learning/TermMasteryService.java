package com.finsight.learning;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TermMasteryService {
    public static final double WELL_KNOWN_THRESHOLD = 0.8;
    public static final int WELL_KNOWN_MIN_ATTEMPTS = 3;
    public static final double REVIEW_THRESHOLD = 0.5;
    public record Mastery(String term, double pKnown, double effectivePKnown, int attempts, Instant lastAnsweredAt, String level, Boolean lastCorrect) {
        public Mastery(String term, double pKnown, double effectivePKnown, int attempts, Instant lastAnsweredAt, String level) {
            this(term, pKnown, effectivePKnown, attempts, lastAnsweredAt, level, null);
        }
    }
    public record Overview(List<Mastery> strongest, List<Mastery> weakest, int totalAttempts, int learningCount, List<Mastery> learning) {}
    public record BackfillCount(long concepts, long savedAnswers) {}
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Value("${finsight.learning.daily-credit-enabled:false}")
    private boolean dailyCreditEnabled;
    private Clock clock = Clock.system(ZoneId.of("Asia/Seoul"));
    public TermMasteryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public List<Mastery> list(Long userId) {
        Instant now = clock.instant();
        String source = dailyCreditEnabled
                ? "SELECT level,correct FROM learning_mastery_credit WHERE user_id=m.user_id AND term=m.term AND question_type='DEFINITION' ORDER BY answered_at DESC,id DESC LIMIT 1"
                : "SELECT level,correct FROM learning_progress WHERE user_id=m.user_id AND term=m.term AND question_type='DEFINITION' ORDER BY answered_at DESC, id DESC LIMIT 1";
        return jdbc.query("""
                SELECT m.*, q.level, q.correct FROM term_mastery m
                JOIN term t ON t.term=m.term
                LEFT JOIN LATERAL (%s) q ON true
                WHERE m.user_id=? AND m.definition_only AND (t.kind='CONCEPT' OR (t.kind='MARKET' AND q.level='beginner')) ORDER BY m.term
                """.formatted(source), (rs, index) -> {
            Instant at = rs.getTimestamp("last_answered_at") == null ? null : rs.getTimestamp("last_answered_at").toInstant();
            long days = at == null ? 0 : ChronoUnit.DAYS.between(at.atZone(clock.getZone()).toLocalDate(), now.atZone(clock.getZone()).toLocalDate());
            double p = rs.getDouble("p_known");
            return new Mastery(rs.getString("term"), p, KnowledgeTracing.decay(p, days), rs.getInt("attempts"), at, rs.getString("level"), rs.getObject("correct", Boolean.class));
        }, userId);
    }

    /** Credits only the first daily article/concept answer; disabled mode keeps practice without BKT writes. */
    @Transactional
    public boolean answer(Long userId, Long newsId, String term, String level, boolean correct, int optionCount, QuestionType type) {
        if (!dailyCreditEnabled) return false;
        if (optionCount != 3) throw new IllegalArgumentException("Expected three quiz options");
        // One PostgreSQL uniqueness decision, in the same transaction as progress and BKT.
        int first = jdbc.update("""
                INSERT INTO learning_mastery_credit(user_id,news_id,term,quiz_day,level,correct,question_type,answered_at)
                VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(user_id,news_id,term,quiz_day) DO NOTHING
                """,userId,newsId,term,LocalDate.now(clock),level,correct,type.name(),java.sql.Timestamp.from(clock.instant()));
        if (first == 0) return false;
        if (type == QuestionType.DEFINITION) answer(userId,term,level,correct);
        else if (type == QuestionType.DIRECTION || type == QuestionType.FACT_CHECK) applicationAnswer(userId,term,type,correct);
        else return false;
        return true;
    }

    boolean applicationEnabled() { return dailyCreditEnabled; }

    @Transactional(readOnly=true)
    public Map<String,Double> applicationProbabilities(Long userId,String level) {
        if (!dailyCreditEnabled || "beginner".equals(level)) return Map.of();
        String type="normal".equals(level)?"DIRECTION":"FACT_CHECK";
        Map<String,Double> values=new HashMap<>();
        jdbc.query("SELECT term,p_known,last_answered_at FROM term_application_mastery WHERE user_id=? AND question_type=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> values.put(rs.getString("term"),effective(rs.getDouble("p_known"),
                        rs.getTimestamp("last_answered_at").toInstant(),clock.instant(),clock.getZone())),userId,type);
        return values;
    }
    void applicationAnswer(Long userId,String term,QuestionType type,boolean correct) {
        Integer eligible=jdbc.queryForObject("SELECT count(*) FROM term WHERE term=? AND kind='CONCEPT'",Integer.class,term);
        if(eligible==null || eligible==0) return;
        jdbc.update("INSERT INTO term_application_mastery(user_id,term,question_type,p_known,attempts,last_answered_at,last_correct) VALUES(?,?,?,?,0,?,false) ON CONFLICT DO NOTHING",
                userId,term,type.name(),KnowledgeTracing.P_INIT,java.sql.Timestamp.from(clock.instant()));
        record Stored(double p,int attempts,Instant at) {}
        var stored=jdbc.query("SELECT p_known,attempts,last_answered_at FROM term_application_mastery WHERE user_id=? AND term=? AND question_type=? FOR UPDATE",
                (rs,i) -> new Stored(rs.getDouble("p_known"),rs.getInt("attempts"),rs.getTimestamp("last_answered_at").toInstant()),userId,term,type.name()).getFirst();
        String evidenceLevel=type==QuestionType.DIRECTION?"normal":"analyst";
        jdbc.update("UPDATE term_application_mastery SET p_known=?,attempts=?,last_answered_at=?,last_correct=? WHERE user_id=? AND term=? AND question_type=?",
                KnowledgeTracing.update(effective(stored.p(),stored.at(),clock.instant(),clock.getZone()),correct,evidenceLevel),stored.attempts()+1,
                java.sql.Timestamp.from(clock.instant()),correct,userId,term,type.name());
    }

    @Transactional
    public void answer(Long userId, String term, String level, boolean correct) {
        Integer eligible = jdbc.queryForObject("SELECT count(*) FROM term WHERE term=? AND (kind='CONCEPT' OR (kind='MARKET' AND ?='beginner'))", Integer.class, term, level);
        if (eligible == null || eligible == 0) return;
        jdbc.update("INSERT INTO term_mastery(user_id,term,p_known,attempts,definition_only) VALUES(?,?,?,0,true) ON CONFLICT DO NOTHING", userId, term, KnowledgeTracing.P_INIT);
        record Stored(double p, int attempts, Instant at, boolean definitionOnly) {}
        Stored stored = jdbc.query("SELECT p_known,attempts,last_answered_at,definition_only FROM term_mastery WHERE user_id=? AND term=? FOR UPDATE",
                (rs, index) -> new Stored(rs.getDouble("p_known"), rs.getInt("attempts"),
                        rs.getTimestamp("last_answered_at") == null ? null : rs.getTimestamp("last_answered_at").toInstant(), rs.getBoolean("definition_only")), userId, term).getFirst();
        Instant now = clock.instant();
        double prior = stored.definitionOnly() ? effective(stored.p(), stored.at(), now, clock.getZone()) : KnowledgeTracing.P_INIT;
        // Definition questions have the same three-option format at every reading level.
        jdbc.update("UPDATE term_mastery SET p_known=?,attempts=?,last_answered_at=?,definition_only=true WHERE user_id=? AND term=?",
                KnowledgeTracing.update(prior, correct, "beginner"), (stored.definitionOnly() ? stored.attempts() : 0) + 1,
                java.sql.Timestamp.from(now), userId, term);
    }

    static double effective(double probability, Instant answeredAt, Instant now, ZoneId zone) {
        long days = answeredAt == null ? 0 : ChronoUnit.DAYS.between(answeredAt.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate());
        return KnowledgeTracing.decay(probability, days);
    }

    public static Overview overview(List<Mastery> rows) {
        Comparator<Mastery> byProbability = Comparator.comparingDouble(Mastery::effectivePKnown).thenComparing(Mastery::term);
        return new Overview(rows.stream().filter(TermMasteryService::wellKnown).sorted(byProbability.reversed()).limit(3).toList(),
                rows.stream().filter(row -> needsReview(row.effectivePKnown(), row.lastCorrect())).sorted(byProbability).limit(10).toList(),
                rows.stream().mapToInt(Mastery::attempts).sum(),
                (int) rows.stream().filter(row -> !wellKnown(row) && !needsReview(row.effectivePKnown(), row.lastCorrect())).count(),
                rows.stream().filter(row -> !wellKnown(row) && !needsReview(row.effectivePKnown(), row.lastCorrect())).sorted(byProbability).toList());
    }

    public static boolean needsReview(double probability, Boolean lastCorrect) {
        return probability < REVIEW_THRESHOLD || Boolean.FALSE.equals(lastCorrect);
    }

    public static boolean wellKnown(Mastery row) {
        // A recent wrong answer takes precedence so the same concept cannot occupy both groups.
        return row.effectivePKnown() >= WELL_KNOWN_THRESHOLD && row.attempts() >= WELL_KNOWN_MIN_ATTEMPTS
                && !Boolean.FALSE.equals(row.lastCorrect());
    }

    public static String suggestion(List<Mastery> rows, String level) {
        if (!List.of("beginner", "normal").contains(Objects.toString(level, ""))) return null;
        return rows.stream().filter(row -> level.equals(row.level()) && row.effectivePKnown() >= 0.85 && wellKnown(row)).map(Mastery::term).distinct().count() >= 5
                ? "beginner".equals(level) ? "normal" : "analyst" : null;
    }

    @Transactional(readOnly = true)
    public BackfillCount backfillCount() {
        Long concepts = jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT DISTINCT p.user_id,p.term FROM learning_progress p
                JOIN term t ON t.term=p.term
                WHERE p.question_type='DEFINITION' AND (t.kind='CONCEPT' OR (t.kind='MARKET' AND p.level='beginner')) AND NOT EXISTS
                (SELECT 1 FROM term_mastery m WHERE m.user_id=p.user_id AND m.term=p.term AND m.definition_only)) missing
                """, Long.class);
        Long answers = jdbc.queryForObject("""
                SELECT count(*) FROM learning_progress p JOIN term t ON t.term=p.term
                WHERE p.question_type='DEFINITION' AND (t.kind='CONCEPT' OR (t.kind='MARKET' AND p.level='beginner')) AND NOT EXISTS
                (SELECT 1 FROM term_mastery m WHERE m.user_id=p.user_id AND m.term=p.term AND m.definition_only)
                """, Long.class);
        return new BackfillCount(concepts, answers);
    }

    /** Manual maintenance only. Replays saved latest answers, never overwrites an existing mastery. */
    @Transactional
    public int backfillMissing() {
        record Key(Long userId, String term) {}
        record State(double p, int attempts, Instant at) {}
        Map<Key, State> states = new LinkedHashMap<>();
        jdbc.query("""
                SELECT p.* FROM learning_progress p JOIN term t ON t.term=p.term
                WHERE p.question_type='DEFINITION' AND (t.kind='CONCEPT' OR (t.kind='MARKET' AND p.level='beginner')) AND NOT EXISTS
                (SELECT 1 FROM term_mastery m WHERE m.user_id=p.user_id AND m.term=p.term AND m.definition_only)
                ORDER BY p.answered_at,p.id
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            var key = new Key(rs.getLong("user_id"), rs.getString("term"));
            var prior = states.getOrDefault(key, new State(KnowledgeTracing.P_INIT, 0, null));
            Instant answeredAt = rs.getTimestamp("answered_at").toInstant();
            states.put(key, new State(KnowledgeTracing.update(effective(prior.p(), prior.at(), answeredAt, clock.getZone()),
                    rs.getBoolean("correct"), "beginner"), prior.attempts() + 1, answeredAt));
        });
        int inserted = 0;
        for (var entry : states.entrySet()) inserted += jdbc.update("""
                INSERT INTO term_mastery(user_id,term,p_known,attempts,last_answered_at,definition_only) VALUES(?,?,?,?,?,true)
                ON CONFLICT (user_id,term) DO UPDATE SET p_known=EXCLUDED.p_known,attempts=EXCLUDED.attempts,
                last_answered_at=EXCLUDED.last_answered_at,definition_only=true WHERE NOT term_mastery.definition_only
                """, entry.getKey().userId(), entry.getKey().term(), entry.getValue().p(), entry.getValue().attempts(), java.sql.Timestamp.from(entry.getValue().at()));
        return inserted;
    }
}
