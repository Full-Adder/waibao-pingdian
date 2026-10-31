package com.waibao.pingdian.complaint.repo;

import com.waibao.pingdian.complaint.domain.ComplaintBlocklist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ComplaintBlocklistRepository extends JpaRepository<ComplaintBlocklist, UUID> {

    List<ComplaintBlocklist> findByUscc(String uscc);
}
