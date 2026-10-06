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
    public record WeakArea(String category, int decidedCount, int hitCount, List<Long> recentNewsIds) {}
    public record Progress(Counts today, Week week, int activityStreakDays, List<Long> completedNewsIds,
                           int totalDecidedCount, List<WeakArea> weakAreas) {}
    private final JdbcTemplate jdbc;
    private final NewsCategoryClassifier categories;
    private Clock clock = Clock.system(ZoneId.of("Asia/Seoul"));
    public LearningProgressService(JdbcTemplate jdbc, NewsCategoryClassifier categories) {
        this.jdbc = jdbc; this.categories = categories;
    }
    @Transactional(readOnly = true)
    public Progress get(Long userId) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        var records = jdbc.queryForList("SELECT news_id,correct,answered_at FROM learning_progress WHERE user_id=? AND answered_at>=? AND answered_at<=? ORDER BY answered_at DESC",
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
        var decisions = jdbc.queryForList("SELECT j.choice,j.actual_direction,n.id AS news_id,n.title,n.raw_content FROM judgement j JOIN news n ON n.id=j.news_id WHERE j.user_id=? AND j.feedback_generated_at IS NOT NULL AND j.actual_direction IN ('UP','NEUTRAL','DOWN') AND j.actual_change_percent IS NOT NULL ORDER BY j.created_at DESC", userId);
        Map<String,List<Map<String,Object>>> fields = new LinkedHashMap<>();
        for (var row:decisions) {
            String category = categories.classify((String)row.get("title"),(String)row.get("raw_content"));
            fields.computeIfAbsent(category,ignored -> new ArrayList<>()).add(row);
        }
        List<WeakArea> weak = fields.entrySet().stream().filter(entry -> entry.getValue().size()>=2).map(entry -> {
            int hits=(int)entry.getValue().stream().filter(row -> Objects.equals(row.get("choice"),row.get("actual_direction"))).count();
            List<Long> ids=entry.getValue().stream().map(row -> ((Number)row.get("news_id")).longValue()).distinct().limit(2).toList();
            return new WeakArea(entry.getKey(),entry.getValue().size(),hits,ids);
        }).filter(area -> (double)area.hitCount()/area.decidedCount()<0.6)
                .sorted(Comparator.comparingDouble(area -> (double)area.hitCount()/area.decidedCount())).limit(3).toList();
        return new Progress(new Counts(todayCount,todayCorrect,accuracy(todayCount,todayCorrect)),
                new Week(monday,monday.plusDays(7),weekCount,weekCorrect,accuracy(weekCount,weekCorrect)),
                streak,List.copyOf(completed),decisions.size(),decisions.size()<3?List.of():weak);
    }
    private Double accuracy(int count,int correct) { return count==0?null:(double)correct/count; }
}
