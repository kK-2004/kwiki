package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;

import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.AuthorizationScope;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.attach.AttachmentStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 已授权的引用解析：一个子分块 key 可解析出页面、已发布
 * 修订版本、父分块 key、标题路径、字符区间与摘录 —— 但仅限于
 * 调用方当前的作用域之内。访问权已被撤销时不返回任何受限
 * 元数据。块内多模态资源以 type + contentId 暴露；预览 URL
 * 只在授权检查通过后按 contentId 即时签发，绝不持久化。
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
    private final AttachmentStorage storage;

    public CitationService(ChunkLookup lookup, AuthorizationScopeResolver scopes) {
        this(lookup, scopes, null, null);
    }

    public CitationService(ChunkLookup lookup, AuthorizationScopeResolver scopes,
                           ResourceAuthorizationService resources) {
        this(lookup, scopes, resources, null);
    }

    @Autowired
    public CitationService(ChunkLookup lookup, AuthorizationScopeResolver scopes,
                           ResourceAuthorizationService resources,
                           AttachmentStorage storage) {
        this.lookup = lookup;
        this.scopes = scopes;
        this.resources = resources;
        this.storage = storage;
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("childChunkKey", hit.chunkKey());
        result.put("parentChunkKey", hit.parentChunkKey());
        result.put("resourceType", hit.resourceType());
        result.put("resourceId", hit.resourceId());
        result.put("revisionId", hit.revisionId() == null ? -1L : hit.revisionId());
        result.put("headingPath", hit.headingPath());
        result.put("charStart", hit.charStart());
        result.put("charEnd", hit.charEnd());
        result.put("excerpt", hit.content().length() <= 160
                ? hit.content() : hit.content().substring(0, 160));
        // 多模态资源：授权已在上方完成（知识库作用域 + 页面读取），
        // 预览链接按 contentId 即时签发；URL 只出现在本次响应中。
        result.put("resources", imageResources(hit));
        return result;
    }

    private List<Map<String, Object>> imageResources(ChunkHit hit) {
        List<Map<String, Object>> resourcesOut = new ArrayList<>();
        for (Long contentId : hit.contentIds() == null ? List.<Long>of() : hit.contentIds()) {
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("type", "image");
            resource.put("contentId", contentId);
            if (storage != null) {
                try {
                    resource.put("previewUrl", storage.cdnLink(contentId));
                } catch (Exception unresolved) {
                    // 链接签发失败：仍暴露身份，不暴露内部细节
                    resource.put("previewUrl", null);
                }
            }
            resourcesOut.add(resource);
        }
        return resourcesOut;
    }

    /** 函数式适配器，使测试与 ES 实现保持极简。 */
    public static ChunkLookup fromFunction(Function<String, Optional<ChunkHit>> function) {
        return function::apply;
    }
}
