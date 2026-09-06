package com.kwiki.wiki.api;

import com.kk2004.common.exception.BusinessException;
import com.kk2004.common.exception.NotFoundException;
import com.kk2004.common.response.TransDTO;
import com.kwiki.indexing.job.IndexingJobStore;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Administrator-only visibility into the indexing queue with sanitized failures. */
@RestController
@RequestMapping("/api/v1/admin/indexing-jobs")
@PreAuthorize("hasRole('ADMIN')")
public class AdminIndexingJobController {

    private final IndexingJobStore jobs;

    public AdminIndexingJobController(IndexingJobStore jobs) {
        this.jobs = jobs;
    }

    @GetMapping
    TransDTO<List<Map<String, Object>>> list(@RequestParam(required = false) String state,
                                             @RequestParam(defaultValue = "50") int limit) {
        return TransDTO.success(jobs.list(state, Math.clamp(limit, 1, 200)));
    }

    @GetMapping("/{jobId}")
    TransDTO<Map<String, Object>> detail(@PathVariable long jobId) {
        return jobs.findById(jobId)
                .map(TransDTO::success)
                .orElseThrow(() -> new NotFoundException("indexing job not found"));
    }

    /** Re-opens a terminal FAILED job for a fresh processing run. */
    @PostMapping("/{jobId}/retry")
    TransDTO<Map<String, Object>> retry(@PathVariable long jobId) {
        boolean reopened = jobs.adminRetry(jobId);
        if (!reopened) {
            throw new BusinessException(409, "not_retryable");
        }
        return TransDTO.success(Map.of("state", "PENDING"));
    }
}
