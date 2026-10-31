package com.waibao.pingdian.account.repo;

import com.waibao.pingdian.account.domain.RiskSignal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RiskSignalRepository extends JpaRepository<RiskSignal, UUID> {
}
