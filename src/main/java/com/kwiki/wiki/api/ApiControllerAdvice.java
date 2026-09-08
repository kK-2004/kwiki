package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * App-owned handlers for failures the kk-common shared exception auto-configuration
 * does not cover, so no exception type ever has two owners: Spring Security
 * authorization denials (@PreAuthorize) and defensive bad-request arguments. Everything
 * else (not-found, conflict, validation, unknown) is answered by the SDK's
 * GlobalExceptionHandler with the shared TransDTO envelope. Responses carry stable
 * codes only — never stack traces, constraint names, or exception internals.
 *
 * <p>{@code HIGHEST_PRECEDENCE} is load-bearing: the SDK advice declares the same
 * order, and its catch-all {@code Exception} handler would otherwise be consulted
 * first, flattening 403/400 into the shared system-error code. Equal orders resolve
 * through Spring's stable sort, which keeps this component-scanned (user) bean ahead
 * of the SDK's auto-configured one.
 */
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ApiControllerAdvice {

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<TransDTO<String>> uploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(TransDTO.failure(413, "文件过大，请选择不超过 20 MB 的文件"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<TransDTO<String>> forbidden(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(TransDTO.failure(HttpStatus.FORBIDDEN.value(), "forbidden"));
    }

    @ExceptionHandler(WikiImportValidationException.class)
    ResponseEntity<TransDTO<String>> invalidImport(WikiImportValidationException e) {
        return ResponseEntity.badRequest().body(TransDTO.failure(400, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<TransDTO<String>> invalidRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(TransDTO.failure(HttpStatus.BAD_REQUEST.value(), "invalid_request"));
    }
}
