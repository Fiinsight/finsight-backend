package com.finsight.learning;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.finsight.news.collect.NewsCategoryClassifier;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
class LearningProgressServiceTest {
 @Test void lastAnswersKstMondayAndOwnerActivityRemainIndependent() {
  var db=mock(JdbcTemplate.class);var profiles=mock(com.finsight.auth.UserProfileService.class);
  when(profiles.getOnboarding(anyLong())).thenReturn(new com.finsight.auth.UserProfileDtos.OnboardingResponse(List.of(),"beginner","short","news","뉴스 하나 읽기",null));
  var symbols=mock(com.finsight.news.collect.NewsSymbolMatcher.class); when(symbols.match(any(),any())).thenReturn("KOSPI");
  var service=new LearningProgressService(db,new NewsCategoryClassifier(),profiles,symbols, mock(TermMasteryService.class));
  var now=Instant.parse("2026-10-04T15:10:00Z"); // Monday 00:10 KST
  org.springframework.test.util.ReflectionTestUtils.setField(service,"clock",Clock.fixed(now,ZoneId.of("Asia/Seoul")));
  var quiz=Map.<String,Object>of("news_id",115L,"correct",false,"answered_at",java.sql.Timestamp.from(now));
  var old=Map.<String,Object>of("news_id",116L,"correct",true,"answered_at",java.sql.Timestamp.from(now.minusSeconds(1200)));
  when(db.queryForList(contains("learning_progress"),eq(1L),any(),any())).thenReturn(List.of(quiz,old));
  when(db.query(contains("UNION"),any(RowMapper.class),eq(1L),any(),eq(1L),any(),eq(1L),any())).thenReturn(List.of(LocalDate.of(2026,10,5),LocalDate.of(2026,10,4),LocalDate.of(2026,10,3)));
  var wrong=Map.<String,Object>of("choice","UP","actual_direction","DOWN","news_id",115L,"related_symbol","KOSPI","title","금리 동결","raw_content","금리 환경");
  var single=Map.<String,Object>of("choice","UP","actual_direction","DOWN","news_id",117L,"related_symbol","KOSPI","title","환율 상승","raw_content","달러");
  when(db.queryForList(contains("JOIN news"),eq(1L),any(),any())).thenReturn(List.of(wrong,wrong,wrong,wrong,wrong,single));
  var response=service.get(1L);
  verify(db).queryForList(contains("j.comparison_date IS NOT NULL"), eq(1L),any(),any());
  assertEquals(LocalDate.of(2026,10,5),response.week().from());assertEquals(1,response.week().answeredCount());
  assertEquals(0.0,response.today().accuracy());assertEquals(3,response.activityStreakDays());
  assertEquals(1,response.weakAreas().size());assertEquals(5,response.weakAreas().get(0).decidedCount());
  when(db.queryForList(contains("learning_progress"),eq(1L),any(),any())).thenReturn(List.of(old));
  assertEquals(3,service.get(1L).activityStreakDays()); // quiz re-answer does not rewrite activity dates
  assertEquals(0,service.get(2L).activityStreakDays());assertTrue(service.get(2L).completedNewsIds().isEmpty());
  var many=new ArrayList<Map<String,Object>>();
  for(long id=0;id<102;id++)many.add(Map.of("news_id",id,"correct",true,"answered_at",java.sql.Timestamp.from(now)));
  when(db.queryForList(contains("learning_progress"),eq(1L),any(),any())).thenReturn(many);
  assertEquals(100,service.get(1L).completedNewsIds().size());
  when(db.queryForList(contains("JOIN news"),eq(1L),any(),any())).thenReturn(List.of(wrong,wrong));
  assertTrue(service.get(1L).weakAreas().isEmpty());
  var right=new HashMap<>(wrong); right.put("actual_direction","UP");
  when(db.queryForList(contains("JOIN news"),eq(1L),any(),any())).thenReturn(List.of(wrong,wrong,wrong,right,right));
  assertEquals(5,service.get(1L).weakAreas().get(0).sampleSize()); // exactly 60% mismatched
  when(db.queryForList(contains("JOIN news"),eq(1L),any(),any())).thenReturn(List.of(wrong,wrong,right,right,right));
  assertTrue(service.get(1L).weakAreas().isEmpty());
  when(symbols.match(any(),any())).thenReturn(null);
  when(db.queryForList(contains("JOIN news"),eq(1L),any(),any())).thenReturn(List.of(wrong,wrong,wrong,wrong,wrong));
  assertEquals(0,service.get(1L).totalDecidedCount());
  verify(db,atLeastOnce()).queryForList(contains("j.created_at>=?"),eq(1L),any(),any());
  var readGoal=service.get(1L,List.of(LocalDate.of(2026,10,5),LocalDate.of(2026,10,4)));
  assertTrue(readGoal.goal().achievedToday()); assertEquals(2,readGoal.goal().streakDays());
  verify(db,atLeastOnce()).queryForList(contains("JOIN news"),eq(2L),any(),any());
 }
 @Test void savedActivitiesDriveEachGoalWithoutReadPersistence() {
  var db=mock(JdbcTemplate.class); var profiles=mock(com.finsight.auth.UserProfileService.class);
  var service=new LearningProgressService(db,new NewsCategoryClassifier(),profiles,new com.finsight.news.collect.NewsSymbolMatcher(), mock(TermMasteryService.class));
  var date=LocalDate.of(2026,10,12); var now=date.atStartOfDay(ZoneId.of("Asia/Seoul")).plusHours(12).toInstant();
  org.springframework.test.util.ReflectionTestUtils.setField(service,"clock",Clock.fixed(now,ZoneId.of("Asia/Seoul")));
  for(String type:List.of("memo","reviewJudgement","minutes5")) {
   when(profiles.getOnboarding(1L)).thenReturn(new com.finsight.auth.UserProfileDtos.OnboardingResponse(List.of(),"beginner","short","news","",null,"terms",type));
   when(db.queryForList(contains("activities"),eq(1L),eq(1L),eq(1L),any(),eq(type),any())).thenReturn(List.of(Map.of("day",java.sql.Date.valueOf(date),"count",2L)));
   assertEquals(!type.equals("minutes5"),service.get(1L).goal().achievedToday());
   when(db.queryForList(contains("activities"),eq(1L),eq(1L),eq(1L),any(),eq(type),any())).thenReturn(List.of(Map.of("day",java.sql.Date.valueOf(date),"count",3L)));
   assertTrue(service.get(1L).goal().achievedToday());
   when(db.queryForList(contains("activities"),eq(1L),eq(1L),eq(1L),any(),eq(type),any())).thenReturn(List.of());
   assertEquals(type.equals("reviewJudgement"),service.get(1L,List.of(),List.of(date,date.plusDays(1))).goal().achievedToday());
  }
 }
}
