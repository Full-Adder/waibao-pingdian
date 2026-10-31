package com.waibao.pingdian.company.repo;

import com.waibao.pingdian.company.domain.CompanyAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CompanyAliasRepository extends JpaRepository<CompanyAlias, UUID> {

    List<CompanyAlias> findByUscc(String uscc);
}
