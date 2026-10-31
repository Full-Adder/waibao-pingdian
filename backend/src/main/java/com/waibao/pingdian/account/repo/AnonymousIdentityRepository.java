package com.waibao.pingdian.account.repo;

import com.waibao.pingdian.account.domain.AnonymousIdentity;
import com.waibao.pingdian.account.domain.IdentityStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnonymousIdentityRepository extends JpaRepository<AnonymousIdentity, UUID> {

    boolean existsByAlias(String alias);

    Optional<AnonymousIdentity> findFirstByUserIdAndStatusOrderByCreatedAtAsc(UUID userId, IdentityStatus status);

    List<AnonymousIdentity> findByUserIdInAndStatus(Collection<UUID> userIds, IdentityStatus status);

    @Query("select a.userId from AnonymousIdentity a where lower(a.alias) like lower(concat('%', :keyword, '%'))")
    List<UUID> findUserIdsByAliasContaining(@Param("keyword") String keyword);
}
