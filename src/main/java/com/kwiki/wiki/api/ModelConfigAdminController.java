package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.infrastructure.ai.registry.ModelConfigAdminService;
import com.kwiki.infrastructure.ai.registry.ModelConfigAdminService.AssignmentRequest;
import com.kwiki.infrastructure.ai.registry.ModelConfigAdminService.AssignmentView;
import com.kwiki.infrastructure.ai.registry.ModelConfigAdminService.ConnectionTest;
import com.kwiki.infrastructure.ai.registry.ModelConfigAdminService.ModelRequest;
import com.kwiki.infrastructure.ai.registry.ModelConfigAdminService.ModelView;
import com.kwiki.infrastructure.ai.registry.ModelConfigAdminService.ProviderRequest;
import com.kwiki.infrastructure.ai.registry.ModelConfigAdminService.ProviderView;
import com.kwiki.infrastructure.ai.registry.ModelConfigAdminService.StatusView;
import com.kwiki.infrastructure.ai.registry.ModelConfigAdminService.TestResult;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端模型配置：provider 与模型目录、按用途的模型分配、连通性测试与加载状态。
 * 保存后立即在本实例生效，其他实例通过广播或轮询跟进；响应中 API key 只以掩码出现。
 */
@RestController
@RequestMapping("/api/v1/admin/models")
@PreAuthorize("hasRole('ADMIN')")
public class ModelConfigAdminController {

    private final ModelConfigAdminService service;

    public ModelConfigAdminController(ModelConfigAdminService service) {
        this.service = service;
    }

    @GetMapping("/providers")
    TransDTO<List<ProviderView>> providers() {
        return TransDTO.success(service.providers());
    }

    @PostMapping("/providers")
    TransDTO<ProviderView> createProvider(@RequestBody ProviderRequest request) {
        return TransDTO.success(service.createProvider(request));
    }

    @PutMapping("/providers/{id}")
    TransDTO<ProviderView> updateProvider(@PathVariable long id, @RequestBody ProviderRequest request) {
        return TransDTO.success(service.updateProvider(id, request));
    }

    @DeleteMapping("/providers/{id}")
    TransDTO<Boolean> deleteProvider(@PathVariable long id) {
        service.deleteProvider(id);
        return TransDTO.success(true);
    }

    @PostMapping("/providers/test")
    TransDTO<TestResult> testProvider(@RequestBody ConnectionTest request) {
        return TransDTO.success(service.testProvider(request));
    }

    @PostMapping("/providers/{id}/discover")
    TransDTO<List<String>> discover(@PathVariable long id) {
        return TransDTO.success(service.discover(id));
    }

    @PostMapping("/providers/{id}/models")
    TransDTO<List<ModelView>> createModels(@PathVariable long id, @RequestBody List<ModelRequest> requests) {
        return TransDTO.success(service.createModels(id, requests));
    }

    @PutMapping("/models/{id}")
    TransDTO<ModelView> updateModel(@PathVariable long id, @RequestBody ModelRequest request) {
        return TransDTO.success(service.updateModel(id, request));
    }

    @DeleteMapping("/models/{id}")
    TransDTO<Boolean> deleteModel(@PathVariable long id) {
        service.deleteModel(id);
        return TransDTO.success(true);
    }

    @PostMapping("/models/{id}/test")
    TransDTO<TestResult> testModel(@PathVariable long id) {
        return TransDTO.success(service.testModel(id));
    }

    @GetMapping("/assignments")
    TransDTO<List<AssignmentView>> assignments() {
        return TransDTO.success(service.assignments());
    }

    @PutMapping("/assignments")
    TransDTO<Map<String, Object>> saveAssignments(@RequestBody List<AssignmentRequest> requests) {
        long version = service.saveAssignments(requests);
        return TransDTO.success(Map.of("version", version, "assignments", service.assignments()));
    }

    @GetMapping("/status")
    TransDTO<StatusView> status() {
        return TransDTO.success(service.status());
    }
}
