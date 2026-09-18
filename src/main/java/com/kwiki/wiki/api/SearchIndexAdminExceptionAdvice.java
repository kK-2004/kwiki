package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.indexing.version.SearchIndexDeletionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 管理 API 错误只返回可操作的稳定代码，不回传异常类型、堆栈或下游正文。 */
@RestControllerAdvice(assignableTypes = SearchIndexAdminController.class)
public class SearchIndexAdminExceptionAdvice {
    @ExceptionHandler(SearchIndexDeletionService.IndexDeletionException.class)
    ResponseEntity<TransDTO<String>> deletionFailure() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(TransDTO.failure(409, "index_deletion_failed"));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<TransDTO<String>> invalidState(IllegalStateException failure) {
        String code = failure.getMessage()!=null && failure.getMessage().contains("active rebuild")
                ? "index_version_busy" : "index_version_state_conflict";
        return ResponseEntity.status(HttpStatus.CONFLICT).body(TransDTO.failure(409, code));
    }
}
