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
 * Recycle-bin surface: paged listing of recoverable archive batches and the
 * restore action. Restore conflicts surface as 409 (wrong state / parent kb
 * still archived) and expiry as 410 with a readable reason.
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
