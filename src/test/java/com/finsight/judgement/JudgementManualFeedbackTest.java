package com.finsight.judgement;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.finsight.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
class JudgementManualFeedbackTest {
 @Test void unknownRegradeDefaultsToDryRunAndCannotSelectAnotherOwner() throws Exception {
  var scheduler=mock(FeedbackScheduler.class);
  var controller=new JudgementController(mock(JudgementService.class),scheduler,mock(UserRepository.class));
  var mvc=MockMvcBuilders.standaloneSetup(controller).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
  when(scheduler.regradeUnknown(7L,false)).thenReturn(new JudgementRegradeResponse(true,0,0,java.util.List.of()));
  when(scheduler.regradeUnknown(7L,true)).thenReturn(new JudgementRegradeResponse(false,0,0,java.util.List.of()));
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L,null,java.util.List.of()));
  try {
   mvc.perform(post("/api/judgements/regrade-unknown").param("userId","999"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(true)).andExpect(jsonPath("$.applied").value(0));
   mvc.perform(post("/api/judgements/regrade-unknown").param("apply","true").param("userId","999"))
      .andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(false));
   verify(scheduler).regradeUnknown(7L,false);verify(scheduler).regradeUnknown(7L,true);verifyNoMoreInteractions(scheduler);
  } finally { SecurityContextHolder.clearContext(); }
 }
 @Test void requestCannotSelectAnotherUserOrProcessAllUsers() throws Exception {
  var scheduler=mock(FeedbackScheduler.class);
  var controller=new JudgementController(mock(JudgementService.class),scheduler,mock(UserRepository.class));
  var mvc=MockMvcBuilders.standaloneSetup(controller).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L,null,java.util.List.of()));
  try {
   mvc.perform(post("/api/judgements/generate-feedback-now").param("userId","999"))
      .andExpect(status().isOk());
   verify(scheduler).runNow(7L);verifyNoMoreInteractions(scheduler);
  } finally { SecurityContextHolder.clearContext(); }
 }
}
