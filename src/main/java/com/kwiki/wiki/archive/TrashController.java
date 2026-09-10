package com.kwiki.wiki.archive;

import com.kwiki.security.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 回收站接口：可恢复的归档批次的分页列表与恢复动作。恢复冲突以 409 暴露
 * （状态错误 / 父级知识库仍处于归档），过期则以 410 并附带可读原因暴露。
 */
@RestController
@RequestMapping("/api/v1/trash")
public class TrashController {

    private final TrashQueryService trash;
    private final ResourceArchiveService archive;

    public TrashController(TrashQueryService trash, ResourceArchiveService archive) {
        this.trash = trash;
        this.archive = archive;
    }

    @GetMapping
    public TrashQueryService.TrashPage list(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(required = false) Long kbId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return trash.list(user, kbId, resourceType, cursor, limit);
    }

    @PostMapping("/{batchId}/restore")
    public ResponseEntity<Object> restore(@AuthenticationPrincipal CurrentUser user,
                                          @PathVariable long batchId) {
        try {
            return ResponseEntity.ok(archive.restore(user, batchId));
        } catch (ResourceArchiveService.ExpiredBatchException e) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", "trash-expired", "message", e.getMessage()));
        } catch (ResourceArchiveService.BatchConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "trash-conflict", "message", e.getMessage()));
        }
    }
}
