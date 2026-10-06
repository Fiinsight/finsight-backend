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
 @Test void requestCannotSelectAnotherUserOrProcessAllUsers() throws Exception {
  var scheduler=mock(FeedbackScheduler.class);
  var controller=new JudgementController(mock(JudgementService.class),scheduler,mock(UserRepository.class));
  var mvc=MockMvcBuilders.standaloneSetup(controller).setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L,null,java.util.List.of()));
  try {
   mvc.perform(post("/api/judgements/generate-feedback-now").param("userId","999"))
      .andExpect(status().isOk()).andExpect(content().string("0건 처리 완료"));
   verify(scheduler).runNow(7L);verifyNoMoreInteractions(scheduler);
  } finally { SecurityContextHolder.clearContext(); }
 }
}
