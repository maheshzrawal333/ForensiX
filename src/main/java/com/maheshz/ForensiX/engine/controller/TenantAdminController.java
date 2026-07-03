package com.maheshz.ForensiX.engine.controller;

import com.maheshz.ForensiX.engine.domain.Tenant;
import com.maheshz.ForensiX.engine.repository.TenantRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Enterprise Administrative Boundary for Case (Tenant) Provisioning.
 * <p>
 * In the ForensiX architecture, a "Tenant" is synonymous with an isolated "Investigative Case".
 * This controller manages the absolute highest level of the data hierarchy.
 * <p>
 * ARCHITECTURAL EXCEPTION: Unlike the Evidence or Folder controllers, endpoints in this class
 * are explicitly whitelisted in the {@code WebConfig} to bypass the {@code TenantInterceptor}.
 * This is structurally necessary because the frontend UI must be able to fetch the list of
 * available cases *before* the investigator can select one and establish their `X-Tenant-ID` context.
 */
@RestController
@RequestMapping("/api/admin/tenants")
public class TenantAdminController {

    private final TenantRepository tenantRepository;

    /**
     * Constructor injection ensures the repository dependency is immutable and thread-safe.
     */
    public TenantAdminController(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /**
     * Provisions a new cryptographic and logical boundary for an Investigative Case.
     * <p>
     * Note: While this currently accepts raw RequestParams for rapid prototyping, in a
     * hardened production environment, this should be transitioned to a `@Valid @RequestBody`
     * DTO to enforce strict naming conventions and prevent SQL injection or XSS payloads
     * in the case name.
     *
     * @param id The unique identifier for the case (e.g., "CASE-2026-001").
     * @param name The human-readable display name for the UI.
     * @return HTTP 200 OK with the finalized Tenant entity mapped to the database.
     */
    @PostMapping
    public ResponseEntity<Tenant> registerTenant(@RequestParam String id, @RequestParam String name) {
        Tenant newTenant = new Tenant(id, name);
        return ResponseEntity.ok(tenantRepository.save(newTenant));
    }

    /**
     * UI Bootstrap Endpoint: Retrieves the global registry of all active cases.
     * <p>
     * This endpoint is invoked by the frontend immediately upon load to populate the
     * "Select Case" dropdown menu. Because it is globally exposed to administrators,
     * it does not require an active Tenant Context.
     *
     * @return HTTP 200 OK with an un-paginated array of all available Tenant entities.
     */
    @GetMapping
    public ResponseEntity<List<Tenant>> getAllTenants() {
        return ResponseEntity.ok(tenantRepository.findAll());
    }

    /**
     * Retrieves the metadata for a specific localized case.
     * <p>
     * Utilizes Java `Optional` mapping to guarantee null-safety. If an investigator
     * requests a case ID that has been purged or does not exist, the API cleanly
     * terminates the request with a standard 404 rather than throwing a 500 Server Error.
     *
     * @param id The UUID or String ID of the requested Tenant.
     * @return HTTP 200 OK if found, or HTTP 404 Not Found if the ID is invalid.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Tenant> getTenant(@PathVariable String id) {
        return tenantRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}