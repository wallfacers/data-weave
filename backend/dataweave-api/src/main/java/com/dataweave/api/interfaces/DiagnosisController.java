package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.master.application.DiagnosisService;
import com.dataweave.master.application.DiagnosisService.FixResult;
import com.dataweave.master.domain.TaskDiagnosis;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 诊断 REST 端点：查看诊断记录、一键修复。
 */
@RestController
@RequestMapping("/api/diagnosis")
public class DiagnosisController {

    private final DiagnosisService diagnosisService;

    public DiagnosisController(DiagnosisService diagnosisService) {
        this.diagnosisService = diagnosisService;
    }

    @GetMapping
    public ApiResponse<List<TaskDiagnosis>> all() {
        return ApiResponse.ok(diagnosisService.all());
    }

    @GetMapping("/{id}")
    public ApiResponse<TaskDiagnosis> get(@PathVariable Long id) {
        TaskDiagnosis d = diagnosisService.get(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Diagnosis not found: " + id));
        return ApiResponse.ok(d);
    }

    @PostMapping("/{id}/fix")
    public ApiResponse<FixResult> fix(@PathVariable Long id,
                         @RequestParam(name = "action", defaultValue = "RERUN") String action) {
        return ApiResponse.ok(diagnosisService.applyFix(id, action));
    }
}
