package com.waibao.pingdian.account.repo;

import com.waibao.pingdian.account.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository
        extends JpaRepository<UserAccount, UUID>, JpaSpecificationExecutor<UserAccount> {

    Optional<UserAccount> findByPhoneHash(String phoneHash);

    boolean existsByPhoneHash(String phoneHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserAccount u set u.contributionScore = u.contributionScore + :delta where u.id = :userId")
    int addContribution(@Param("userId") UUID userId, @Param("delta") int delta);
}
