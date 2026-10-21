package com.kwiki.usage;

import com.kk2004.common.exception.BusinessException;
import com.kk2004.common.response.TransDTO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 模型用量监控管理接口；类级角色校验是全部查询的服务端边界。 */
@RestController
@RequestMapping("/api/v1/admin/model-usage")
@PreAuthorize("hasRole('ADMIN')")
public class ModelUsageAdminController {

    private final ModelUsageQueryService queries;

    public ModelUsageAdminController(ModelUsageQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/users")
    TransDTO<List<ModelUsageQueryService.UserView>> users(@RequestParam String from, @RequestParam String to,
                                                          @RequestParam(required = false) String sources) {
        return TransDTO.success(queries.users(instant(from), instant(to), sources(sources)));
    }

    @GetMapping("/series")
    TransDTO<ModelUsageQueryService.SeriesResponse> series(@RequestParam String from, @RequestParam String to,
                                                          @RequestParam(required = false) String sources,
                                                          @RequestParam(required = false) String users,
                                                          @RequestParam(defaultValue = "auto") String bucket,
                                                          @RequestParam(defaultValue = "480") int tzOffsetMinutes) {
        return TransDTO.success(queries.series(instant(from), instant(to), sources(sources), users(users), bucket,
                tzOffsetMinutes));
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new BusinessException(400, "invalid-time");
        }
    }

    private static Set<UsageSource> sources(String value) {
        Set<UsageSource> sources = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return sources;
        for (String token : value.split(",")) {
            try {
                sources.add(UsageSource.valueOf(token.trim()));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "invalid-source");
            }
        }
        return sources;
    }

    private static Set<Long> users(String value) {
        Set<Long> users = new LinkedHashSet<>();
        if (value == null || value.isBlank()) return users;
        try {
            Arrays.stream(value.split(",")).map(String::trim).map(Long::parseLong).forEach(users::add);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "invalid-user");
        }
        return users;
    }
}
