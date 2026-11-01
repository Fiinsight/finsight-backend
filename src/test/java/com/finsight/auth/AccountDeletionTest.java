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
  var cleanup=mock(AccountDataDeletion.class);
  org.springframework.test.util.ReflectionTestUtils.setField(service,"accountDataDeletion",cleanup);
  service.deleteAccount(7L);
  var order=inOrder(cleanup,users);order.verify(cleanup).deleteForUser(7L);order.verify(users).delete(user);
 }
}
