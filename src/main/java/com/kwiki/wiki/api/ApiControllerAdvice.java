package com.kwiki.wiki.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.kk2004.common.exception.BaseBusinessException;
import com.kk2004.common.exception.CommonErrorCode;
import com.kk2004.common.response.TransDTO;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 用于承载 kk-common 共享异常自动配置未覆盖的失败的应用自有
 * 处理器：Spring Security 的授权拒绝（@PreAuthorize）、防御性的错误请求参数，
 * 以及需要补充可观测日志的共享业务异常。未知异常与系统异常仍由 SDK 的
 * GlobalExceptionHandler 以共享的 TransDTO 信封作答。响应只携带稳定的错误码；
 * 异常堆栈只写服务端日志，绝不进入响应体。
 *
 * <p>{@code HIGHEST_PRECEDENCE} 是关键所在：SDK 的通知类声明了相同的
 * 优先级，否则它的兜底 {@code Exception} 处理器会被优先
 * 采纳，把 403/400 压平成共享的系统错误码。相同优先级会通过
 * Spring 的稳定排序解析，从而让这个被组件扫描（用户定义）的 bean 排在
 * SDK 自动配置的那个之前。
 */
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ApiControllerAdvice {

    private static final Logger log = LoggerFactory.getLogger(ApiControllerAdvice.class);

    /**
     * kk-common 的业务异常处理器负责响应契约但不记录日志；在应用侧接管，
     * 让 NotFound/冲突等已处理异常同样可由 traceId 检索。
     */
    @ExceptionHandler(BaseBusinessException.class)
    TransDTO<String> businessException(BaseBusinessException e) {
        log.warn("handled business exception type={} code={} message={}",
                e.getClass().getSimpleName(), e.getCode(), e.getMessage(), e);
        return TransDTO.failure(e.getCode(), e.getMessage());
    }

    /**
     * 保持 kk-common 原有校验失败响应格式，同时补充此前缺失的服务端日志。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    TransDTO<String> methodArgumentNotValid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("handled method-argument validation exception: {}", message, e);
        return TransDTO.failure(CommonErrorCode.VALIDATION_FAILED.getCode(), message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    TransDTO<String> constraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("handled constraint-violation exception: {}", message, e);
        return TransDTO.failure(CommonErrorCode.VALIDATION_FAILED.getCode(), message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    TransDTO<String> messageNotReadable(HttpMessageNotReadableException e) {
        String message = "请求体格式错误";
        if (e.getCause() instanceof MismatchedInputException mismatch) {
            String fieldPath = mismatch.getPath().stream()
                    .map(JsonMappingException.Reference::getFieldName)
                    .filter(Objects::nonNull)
                    .filter(field -> !field.isBlank())
                    .collect(Collectors.joining("."));
            if (!fieldPath.isBlank()) {
                message = "字段 [" + fieldPath + "] 格式错误";
            }
        }
        log.warn("handled unreadable-request-body exception: {}", message, e);
        return TransDTO.failure(CommonErrorCode.BAD_REQUEST.getCode(), message);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<TransDTO<String>> uploadTooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        log.warn("handled upload-too-large exception: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(TransDTO.failure(413, "文件过大，请选择不超过 20 MB 的文件"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<TransDTO<String>> forbidden(AccessDeniedException e) {
        log.warn("handled access-denied exception: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(TransDTO.failure(HttpStatus.FORBIDDEN.value(), "forbidden"));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<TransDTO<String>> noResource(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        // 未匹配到任何 API 路由时必须回传真实 404，否则客户端会把
        // “功能未开启/路径不存在”当作 200 空数据处理，掩盖真实状态。
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(TransDTO.failure(HttpStatus.NOT_FOUND.value(), "not_found"));
    }

    @ExceptionHandler(WikiImportValidationException.class)
    ResponseEntity<TransDTO<String>> invalidImport(WikiImportValidationException e) {
        log.warn("handled wiki-import validation exception: {}", e.getMessage(), e);
        return ResponseEntity.badRequest().body(TransDTO.failure(400, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<TransDTO<String>> invalidRequest(IllegalArgumentException e) {
        log.warn("handled invalid-request exception type={} message={}",
                e.getClass().getSimpleName(), e.getMessage(), e);
        return ResponseEntity.badRequest()
                .body(TransDTO.failure(HttpStatus.BAD_REQUEST.value(), "invalid_request"));
    }
}
