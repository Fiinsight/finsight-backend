package com.finsight.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id=:id")
    Optional<User> lockWatchlistOwner(Long id);
    Optional<User> findByEmail(String email);
    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);
}
