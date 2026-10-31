package com.waibao.pingdian.company.repo;

import com.waibao.pingdian.company.domain.Company;
import com.waibao.pingdian.company.domain.CompanyStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, String> {

    Optional<Company> findByName(String name);

    List<Company> findByCityAndIndustryAndStatusOrderByUsccAsc(
            String city, String industry, CompanyStatus status);
}
