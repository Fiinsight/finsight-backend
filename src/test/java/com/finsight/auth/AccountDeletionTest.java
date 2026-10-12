package com.finsight.auth;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.Optional;
import org.springframework.web.reactive.function.client.WebClient;
class AccountDeletionTest {
 @Test void deletesOwnedRowsInOrderBeforeUser() {
  var users = mock(UserRepository.class); var user = mock(User.class); var entityManager = mock(EntityManager.class);
  when(users.findById(7L)).thenReturn(Optional.of(user));
  var service = new AuthService(users, null, WebClient.builder(), "", "", "", "finsight://auth/kakao", "", "https://unused.test", "https://unused.test");
  org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", entityManager);
  var query = mock(Query.class); when(entityManager.createQuery(anyString())).thenReturn(query); when(query.setParameter("userId", 7L)).thenReturn(query);
  service.deleteAccount(7L);
  for (String entity : java.util.List.of("LearningProgress", "ArticleNote", "DailyNote", "Judgement")) verify(entityManager).createQuery("delete from " + entity + " record where record.user.id = :userId");
  verify(query, times(4)).setParameter("userId", 7L);
  var order = inOrder(query, users); order.verify(query, times(4)).executeUpdate(); order.verify(users).delete(user);
 }
}
