package com.kwiki.wiki.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/** 路由未命中必须以真实 404 状态透传，避免被兜底处理器压成 200 空数据。 */
class ApiControllerAdviceNotFoundTest {

    @Test
    void noResourceFoundIsMappedToReal404() {
        var advice = new ApiControllerAdvice();
        var response = advice.noResource(new NoResourceFoundException(
                null, "api/v1/admin/search-indexes/versions"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(404);
        assertThat(response.getBody().getMessage()).isEqualTo("not_found");
    }
}
