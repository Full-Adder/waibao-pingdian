package com.waibao.pingdian.complaint.repo;

import com.waibao.pingdian.complaint.domain.ComplaintEvidence;
import com.waibao.pingdian.complaint.domain.ComplaintParty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ComplaintEvidenceRepository extends JpaRepository<ComplaintEvidence, UUID> {

    List<ComplaintEvidence> findByComplaintIdOrderByCreatedAtAsc(UUID complaintId);

    List<ComplaintEvidence> findByComplaintIdAndSide(UUID complaintId, ComplaintParty side);

    boolean existsByComplaintIdAndSide(UUID complaintId, ComplaintParty side);

    boolean existsByComplaintIdAndSideAndMaterialStatus(UUID complaintId, ComplaintParty side,
                                                       String materialStatus);
}
