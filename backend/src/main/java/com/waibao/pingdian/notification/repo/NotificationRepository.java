package com.waibao.pingdian.notification.repo;

import com.waibao.pingdian.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Notification> findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(UUID userId);

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndTypeAndRefTypeAndRefId(UUID userId, String type, String refType, String refId);

    List<Notification> findByStatusAndChannelInAndRetryCountLessThanOrderByCreatedAtAsc(
            String status, Collection<String> channels, int retryCount);

    @Modifying
    @Query("update Notification n set n.readAt = ?2, n.status = 'READ' "
            + "where n.userId = ?1 and n.readAt is null")
    int markAllRead(UUID userId, Instant readAt);
}
