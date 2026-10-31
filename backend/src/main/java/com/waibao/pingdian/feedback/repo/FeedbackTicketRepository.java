package com.waibao.pingdian.feedback.repo;

import com.waibao.pingdian.feedback.domain.FeedbackStatus;
import com.waibao.pingdian.feedback.domain.FeedbackTicket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FeedbackTicketRepository extends JpaRepository<FeedbackTicket, UUID> {

    Page<FeedbackTicket> findByStatusOrderByCreatedAtDesc(FeedbackStatus status, Pageable pageable);

    Page<FeedbackTicket> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
