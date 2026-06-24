package com.maheshz.openrag.engine.controller;

import com.maheshz.openrag.engine.domain.Tenant;
import com.maheshz.openrag.engine.repository.TenantRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/tenants")
public class TenantAdminController {

    private final TenantRepository tenantRepository;

    public TenantAdminController(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @PostMapping
    public ResponseEntity<Tenant> registerTenant(@RequestParam String id, @RequestParam String name) {
        // This utilizes setId(), setName(), and setCreatedAt()
        Tenant newTenant = new Tenant(id, name);
        return ResponseEntity.ok(tenantRepository.save(newTenant));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Tenant> getTenant(@PathVariable String id) {
        // This utilizes getId(), getName(), etc. for the JSON output
        return tenantRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}