package com.dataweave.api.interfaces;

import com.dataweave.master.domain.Tenant;
import com.dataweave.master.domain.TenantRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 租户管理 CRUD。
 */
@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    private final TenantRepository tenantRepository;

    public TenantController(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @GetMapping
    public List<Tenant> list() {
        return (List<Tenant>) tenantRepository.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Tenant> get(@PathVariable Long id) {
        return tenantRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public Tenant create(@RequestBody Map<String, String> body) {
        Tenant t = new Tenant();
        t.setCode(body.get("code"));
        t.setName(body.get("name"));
        t.setStatus("ACTIVE");
        t.setCreatedBy(1L);
        t.setUpdatedBy(1L);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        t.setDeleted(0);
        t.setVersion(0);
        return tenantRepository.save(t);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Tenant> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return tenantRepository.findById(id)
                .map(existing -> {
                    if (body.containsKey("name")) existing.setName(body.get("name"));
                    if (body.containsKey("status")) existing.setStatus(body.get("status"));
                    existing.setUpdatedAt(LocalDateTime.now());
                    return ResponseEntity.ok(tenantRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
