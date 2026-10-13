package com.finsight.learning;

import com.finsight.news.collect.NewsCategoryClassifier;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningProgressService {
    public record Counts(int answeredCount, int correctCount, Double accuracy) {}
    public record Week(LocalDate from, LocalDate to, int answeredCount, int correctCount, Double accuracy) {}
    public record WeakArea(String category, int decidedCount, int hitCount, List<Long> recentNewsIds, int sampleSize) {}
    public record Progress(Counts today, Week week, int activityStreakDays, List<Long> completedNewsIds,
                           int totalDecidedCount, List<WeakArea> weakAreas, Goal goal, String levelSuggestion, TermMasteryService.Overview mastery) {}
    public record Goal(String type, boolean achievedToday, int streakDays) {}
    private final JdbcTemplate jdbc;
    private final NewsCategoryClassifier categories;
    private final com.finsight.auth.UserProfileService profiles;
    private final com.finsight.news.collect.NewsSymbolMatcher symbols;
    private final TermMasteryService mastery;
    private Clock clock = Clock.system(ZoneId.of("Asia/Seoul"));
    public LearningProgressService(JdbcTemplate jdbc, NewsCategoryClassifier categories, com.finsight.auth.UserProfileService profiles, com.finsight.news.collect.NewsSymbolMatcher symbols, TermMasteryService mastery) {
        this.jdbc = jdbc; this.categories = categories; this.profiles = profiles; this.symbols = symbols; this.mastery = mastery;
    }
    @Transactional(readOnly = true)
    public Progress get(Long userId) { return get(userId, List.of()); }
    @Transactional(readOnly = true)
    public Progress get(Long userId, List<LocalDate> readDays) { return get(userId, readDays, List.of()); }
    @Transactional(readOnly = true)
    public Progress get(Long userId, List<LocalDate> readDays, List<LocalDate> reviewDays) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var records = jdbc.queryForList("SELECT news_id,term,level,correct,answered_at FROM learning_progress WHERE user_id=? AND answered_at>=? AND answered_at<=? ORDER BY answered_at DESC",
                userId, java.sql.Timestamp.from(now.minusDays(30).toInstant()), java.sql.Timestamp.from(now.toInstant()));
        int todayCount=0, todayCorrect=0, weekCount=0, weekCorrect=0;
        LinkedHashSet<Long> completed = new LinkedHashSet<>();
        for (var row : records) {
            Instant at = ((java.sql.Timestamp)row.get("answered_at")).toInstant();
            LocalDate day = at.atZone(now.getZone()).toLocalDate();
            boolean correct = Boolean.TRUE.equals(row.get("correct"));
            if (day.equals(today)) { todayCount++; if (correct) todayCorrect++; }
            if (!day.isBefore(monday)) { weekCount++; if (correct) weekCorrect++; }
            if (completed.size()<100) completed.add(((Number)row.get("news_id")).longValue());
        }
        Set<LocalDate> activity = new HashSet<>(jdbc.query("SELECT DISTINCT (created_at AT TIME ZONE 'Asia/Seoul')::date AS day FROM judgement WHERE user_id=? AND created_at<=? UNION SELECT DISTINCT (created_at AT TIME ZONE 'Asia/Seoul')::date AS day FROM article_note WHERE user_id=? AND created_at<=? UNION SELECT DISTINCT (answered_at AT TIME ZONE 'Asia/Seoul')::date AS day FROM learning_progress WHERE user_id=? AND answered_at<=?",
                (rs,index) -> rs.getDate("day").toLocalDate(), userId, java.sql.Timestamp.from(now.toInstant()), userId, java.sql.Timestamp.from(now.toInstant()), userId, java.sql.Timestamp.from(now.toInstant())));
        LocalDate day = activity.contains(today) ? today : today.minusDays(1);
        int streak=0; while (activity.contains(day)) { streak++; day=day.minusDays(1); }
        var decisions = jdbc.queryForList("SELECT j.choice,j.actual_direction,n.id AS news_id,n.title,n.raw_content,n.related_symbol,j.target_symbol FROM judgement j JOIN news n ON n.id=j.news_id WHERE j.user_id=? AND j.feedback_generated_at IS NOT NULL AND j.actual_direction IN ('UP','NEUTRAL','DOWN') AND j.actual_change_percent IS NOT NULL AND j.comparison_date IS NOT NULL AND j.created_at>=? AND j.created_at<=? ORDER BY j.created_at DESC", userId, java.sql.Timestamp.from(now.minusDays(30).toInstant()), java.sql.Timestamp.from(now.toInstant())).stream()
                .filter(row -> com.finsight.judgement.JudgementTargetResolver.valid((String) row.get("target_symbol")) || (Objects.equals(row.get("related_symbol"), symbols.match((String)row.get("title"), (String)row.get("raw_content"))) && row.get("related_symbol") != null)).toList();
        Map<String,List<Map<String,Object>>> fields = new LinkedHashMap<>();
        for (var row:decisions) {
            String category = categories.classify((String)row.get("title"),(String)row.get("raw_content"));
            fields.computeIfAbsent(category,ignored -> new ArrayList<>()).add(row);
        }
        List<WeakArea> weak = fields.entrySet().stream().filter(entry -> entry.getValue().size()>=5).map(entry -> {
            int hits=(int)entry.getValue().stream().filter(row -> Objects.equals(row.get("choice"),row.get("actual_direction"))).count();
            List<Long> ids=entry.getValue().stream().map(row -> ((Number)row.get("news_id")).longValue()).distinct().limit(2).toList();
            return new WeakArea(entry.getKey(),entry.getValue().size(),hits,ids,entry.getValue().size());
        }).filter(area -> (double)area.hitCount()/area.decidedCount()<=0.4)
                .sorted(Comparator.comparingDouble(area -> (double)area.hitCount()/area.decidedCount())).limit(3).toList();
        var profile = profiles.getOnboarding(userId);
        String type = profile.goalType() == null ? "read1" : profile.goalType();
        Set<LocalDate> achieved = new HashSet<>();
        if ("read1".equals(type)) {
            readDays.stream().filter(date -> !date.isAfter(today)).forEach(achieved::add);
        } else {
            String activitySql = "SELECT (at AT TIME ZONE 'Asia/Seoul')::date AS day, COUNT(*) AS count FROM ("
                + "SELECT created_at AS at,'judgement' AS kind FROM judgement WHERE user_id=? UNION ALL "
                + "SELECT created_at AS at,'memo' AS kind FROM article_note WHERE user_id=? UNION ALL "
                + "SELECT answered_at AS at,'quiz' AS kind FROM learning_progress WHERE user_id=?"
                + ") activities WHERE at<=? AND (?='minutes5' OR kind=?) GROUP BY day";
            String kind = "memo".equals(type) ? "memo" : "judgement";
            var days = jdbc.queryForList(activitySql, userId,userId,userId,java.sql.Timestamp.from(now.toInstant()),type,kind);
            for (var row : days) if (((Number)row.get("count")).intValue() >= ("minutes5".equals(type) ? 3 : 1)) achieved.add(((java.sql.Date)row.get("day")).toLocalDate());
        }
        if ("reviewJudgement".equals(type)) reviewDays.stream().filter(date -> !date.isAfter(today)).forEach(achieved::add);
        var concepts = mastery.list(userId);
        return new Progress(new Counts(todayCount,todayCorrect,accuracy(todayCount,todayCorrect)),
                new Week(monday,monday.plusDays(7),weekCount,weekCorrect,accuracy(weekCount,weekCorrect)),
                streak,List.copyOf(completed),decisions.size(),weak,
                new Goal(type,achieved.contains(today),streak(achieved,today)), TermMasteryService.suggestion(concepts,profile.learningLevel() == null ? "beginner" : profile.learningLevel()), TermMasteryService.overview(concepts));
    }
    static int streak(Set<LocalDate> days, LocalDate today) {
        LocalDate day = days.contains(today) ? today : today.minusDays(1);
        int count = 0;
        while (days.contains(day)) { count++; day = day.minusDays(1); }
        return count;
    }
    private Double accuracy(int count,int correct) { return count==0?null:(double)correct/count; }
}
