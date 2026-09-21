package com.aideep.domain.auth.repository;

import com.aideep.domain.auth.entity.OAuthAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, UUID> {
    Optional<OAuthAccount> findByProviderAndProviderUserIdAndDeletedAtIsNull(String provider, String subject);

    Optional<OAuthAccount> findByUserIdAndProviderAndDeletedAtIsNull(UUID userId, String provider);

    Optional<OAuthAccount> findByUserIdAndProviderAndProviderUserId(UUID userId, String provider, String subject);

    List<OAuthAccount> findByUserIdAndDeletedAtIsNull(UUID userId);

    long countByUserIdAndDeletedAtIsNull(UUID userId);
}
