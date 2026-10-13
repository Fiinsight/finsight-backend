package com.finsight.learning;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
class LearningControllerTest {
 @Test void localDatesAreBoundedAndOnlyForwardedForAuthenticatedOwner() {
  var service=mock(LearningService.class); var progress=mock(LearningProgressService.class);
  var controller=new LearningController(service,progress,mock(LearningRecommendationService.class));
  controller.progress(7L,"2026-10-12","2026-10-11");
  verify(progress).get(7L,List.of(LocalDate.of(2026,10,12)),List.of(LocalDate.of(2026,10,11)));
  for(String invalid:List.of("2026-02-30","garbage","2026-10-12,","x".repeat(4026),"2026-10-12,".repeat(366)+"2026-10-12")) {
   assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.progress(7L,invalid,"")).getStatusCode().value());
  }
  controller.progress(7L,"",""); verify(progress).get(7L,List.of(),List.of());
  verifyNoInteractions(service);
 }
}
