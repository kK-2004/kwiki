package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;

import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.AuthorizationScope;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 已授权的引用解析：一个子分块 key 可解析出页面、已发布
 * 修订版本、父分块 key、标题路径、字符区间与摘录 —— 但仅限于
 * 调用方当前的作用域之内。访问权已被撤销时不返回任何受限
 * 元数据。
 */
@Service
public class CitationService {

    /** 查询端口；Elasticsearch 适配器按文档 id = chunkKey 解析。 */
    public interface ChunkLookup {
        Optional<ChunkHit> byKey(String childChunkKey);
    }

    private final ChunkLookup lookup;
    private final AuthorizationScopeResolver scopes;
    private final ResourceAuthorizationService resources;

    public CitationService(ChunkLookup lookup, AuthorizationScopeResolver scopes) {
        this(lookup, scopes, null);
    }

    @Autowired
    public CitationService(ChunkLookup lookup, AuthorizationScopeResolver scopes,
                           ResourceAuthorizationService resources) {
        this.lookup = lookup;
        this.scopes = scopes;
        this.resources = resources;
    }

    public Map<String, Object> resolve(CurrentUser user, String childChunkKey) {
        ChunkHit hit = lookup.byKey(childChunkKey)
                .orElseThrow(() -> new NotFoundException("citation not found"));
        AuthorizationScope scope = scopes.resolve(user);
        boolean resourceAllowed = resources == null || !"PAGE".equalsIgnoreCase(hit.resourceType())
                || resources.can(user, hit.resourceId(), ResourceAction.READ);
        if (!scope.includes(hit.kbId()) || !resourceAllowed) {
            // 已被撤销或从未授予：与不存在无法区分
            throw new NotFoundException("citation not found");
        }
        return Map.of(
                "childChunkKey", hit.chunkKey(),
                "parentChunkKey", hit.parentChunkKey(),
                "resourceType", hit.resourceType(),
                "resourceId", hit.resourceId(),
                "revisionId", hit.revisionId() == null ? -1L : hit.revisionId(),
                "headingPath", hit.headingPath(),
                "charStart", hit.charStart(),
                "charEnd", hit.charEnd(),
                "excerpt", hit.content().length() <= 160
                        ? hit.content() : hit.content().substring(0, 160));
    }

    /** 函数式适配器，使测试与 ES 实现保持极简。 */
    public static ChunkLookup fromFunction(Function<String, Optional<ChunkHit>> function) {
        return function::apply;
    }
}
