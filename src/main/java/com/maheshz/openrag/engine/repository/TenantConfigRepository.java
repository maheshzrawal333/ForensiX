package com.maheshz.openrag.engine.repository;

import com.maheshz.openrag.engine.domain.TenantConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TenantConfigRepository extends JpaRepository<TenantConfig, String> {
}