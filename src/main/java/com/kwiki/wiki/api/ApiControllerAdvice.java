package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 用于承载 kk-common 共享异常自动配置未覆盖的失败的应用自有
 * 处理器，因此任何一种异常类型都不会有两个归属方：Spring Security
 * 的授权拒绝（@PreAuthorize）与防御性的错误请求参数。其他
 * 一切（未找到、冲突、校验失败、未知）都由 SDK 的
 * GlobalExceptionHandler 以共享的 TransDTO 信封作答。响应只携带稳定的
 * 错误码 —— 绝不包含堆栈、约束名或异常内部信息。
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
