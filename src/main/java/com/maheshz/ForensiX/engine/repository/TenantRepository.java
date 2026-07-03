package com.maheshz.ForensiX.engine.repository;

import com.maheshz.ForensiX.engine.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, String> {
}
