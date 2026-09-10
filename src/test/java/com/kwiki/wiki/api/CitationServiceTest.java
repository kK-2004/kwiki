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
            : Optional.empty();

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
}
