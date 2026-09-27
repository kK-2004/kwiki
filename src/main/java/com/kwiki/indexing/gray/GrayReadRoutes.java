package com.kwiki.indexing.gray;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 读路由与按知识库的解析器查询；每次调用读取一次数据库，切换在下一次查询即生效。 */
@Component
public class GrayReadRoutes {

    private final GrayReleaseStore store;

    public GrayReadRoutes(GrayReleaseStore store) {
        this.store = store;
    }

    public ReadRouting current() {
        Map<String, Set<Long>> grouped = new LinkedHashMap<>();
        for (GrayReleaseStore.SwitchedRoute route : store.switchedRoutes()) {
            grouped.computeIfAbsent(route.physicalName(), key -> new LinkedHashSet<>()).add(route.kbId());
        }
        return new ReadRouting(grouped);
    }

    public Optional<String> switchedParserFor(long kbId) {
        return store.switchedRoutes().stream().filter(route -> route.kbId() == kbId)
                .map(GrayReleaseStore.SwitchedRoute::parserVersion).findFirst();
    }
}
