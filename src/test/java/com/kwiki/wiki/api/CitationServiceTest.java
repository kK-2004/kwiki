package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.security.CurrentUser;
import com.kwiki.testutil.StandardTestProperties;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.infrastructure.redis.ScopeCache;
import com.kwiki.wiki.access.ScopeVersionService;
import com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CitationServiceTest {

    private static final CurrentUser ADMIN = new CurrentUser(1L, "root", true);
    private static final CurrentUser MEMBER = new CurrentUser(7L, "member", false);

    private final CitationService.ChunkLookup lookup = key -> "C-known".equals(key)
            ? Optional.of(new ChunkHit("C-known", "P0", 5L, "PAGE", 7L, 3L, "部署 > MySQL",
                    10, 80, "kwiki 连接外部 MySQL，绝不自建容器"))
            : "C-multimodal".equals(key)
            ? Optional.of(new ChunkHit("C-multimodal", "P1", 5L, "PAGE", 7L, 4L, "架构 > 图",
                    0, 120, "带图块的文本", java.util.List.of(12345L, 12345L, 67890L)))
            : Optional.empty();

    /** 按 contentId 即时签发 CDN 链接的存储桩。 */
    private static final com.kwiki.wiki.attach.AttachmentStorage CDN_STORAGE =
            new com.kwiki.wiki.attach.AttachmentStorage() {
                @Override
                public com.kwiki.wiki.attach.StoredAttachment store(
                        com.kwiki.wiki.attach.AttachmentUpload upload) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public String downloadLink(long id, String name, Duration ttl) {
                    return "https://dl/" + id;
                }

                @Override
                public byte[] readContent(long id) {
                    return new byte[0];
                }

                @Override
                public String cdnLink(long id) {
                    return "https://cdn.example.internal/f/" + id;
                }
            };

    private CitationService service() {
        return new CitationService(lookup, new AuthorizationScopeResolver(
                org.mockito.Mockito.mock(KnowledgeBaseMemberRepository.class),
                new ScopeVersionService(StandardTestProperties.nullProvider()),
                new ScopeCache(StandardTestProperties.nullProvider(), Duration.ofSeconds(60))));
    }

    @Test
    void authorizedCitationResolvesFully() {
        Map<String, Object> citation = service().resolve(ADMIN, "C-known");
        assertThat(citation)
                .containsEntry("childChunkKey", "C-known")
                .containsEntry("parentChunkKey", "P0")
                .containsEntry("resourceType", "PAGE")
                .containsEntry("resourceId", 7L)
                .containsEntry("revisionId", 3L)
                .containsEntry("headingPath", "部署 > MySQL")
                .containsEntry("charStart", 10)
                .containsEntry("charEnd", 80)
                .containsKey("excerpt");
    }

    @Test
    void unknownCitationReturnsNotFound() {
        assertThatThrownBy(() -> service().resolve(ADMIN, "C-missing"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void revokedAccessReturnsNotFoundWithoutRestrictedMetadata() {
        // MEMBER（非管理员）解析出空作用域：不包含知识库 5
        assertThatThrownBy(() -> service().resolve(MEMBER, "C-known"))
                .isInstanceOf(NotFoundException.class)
                .as("revoked access must be indistinguishable from missing");
    }

    @Test
    @SuppressWarnings("unchecked")
    void authorizedMultimodalResourcesExposeDedupedContentIdsAndFreshPreviewUrls() {
        CitationService withStorage = new CitationService(lookup,
                new AuthorizationScopeResolver(
                        org.mockito.Mockito.mock(KnowledgeBaseMemberRepository.class),
                        new ScopeVersionService(StandardTestProperties.nullProvider()),
                        new ScopeCache(StandardTestProperties.nullProvider(),
                                Duration.ofSeconds(60))),
                null, CDN_STORAGE);
        Map<String, Object> citation = withStorage.resolve(ADMIN, "C-multimodal");
        Object resourcesValue = citation.get("resources");
        assertThat(resourcesValue).isInstanceOf(java.util.List.class);
        java.util.List<Map<String, Object>> resources =
                (java.util.List<Map<String, Object>>) resourcesValue;
        // 重复 contentId 去重：只有 12345 与 67890
        assertThat(resources).hasSize(2);
        assertThat(resources.get(0))
                .containsEntry("type", "image")
                .containsEntry("contentId", 12345L)
                .containsEntry("previewUrl", "https://cdn.example.internal/f/12345");
        assertThat(resources.get(1)).containsEntry("contentId", 67890L);
    }

    @Test
    void unauthorizedContentIdLookupYieldsNoResourcesOrUrls() {
        // MEMBER 无知识库作用域：整个引用 404，绝无资源/URL 泄露
        assertThatThrownBy(() -> service().resolve(MEMBER, "C-multimodal"))
                .isInstanceOf(NotFoundException.class);
        // 未注入存储时（旧装配形态）：资源身份仍暴露，且绝无 URL 字段
        Map<String, Object> citation = service().resolve(ADMIN, "C-multimodal");
        assertThat(citation.get("resources")).isInstanceOf(java.util.List.class);
        assertThat((java.util.List<?>) citation.get("resources"))
                .allSatisfy(resource -> {
                    assertThat(((Map<?, ?>) resource).containsKey("previewUrl")).isFalse();
                    assertThat(((Map<?, ?>) resource).containsKey("type")).isTrue();
                });
    }
}
