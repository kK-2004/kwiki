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
 * Authorized citation resolution: a child chunk key resolves to page, published
 * revision, parent key, heading path, character range, and excerpt — but only
 * inside the caller's current scope. Revoked access returns no restricted
 * metadata at all.
 */
@Service
public class CitationService {

    /** Lookup port; the Elasticsearch adapter resolves by document id = chunkKey. */
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
            // revoked or never granted: indistinguishable from missing
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

    /** Function-style adapter so tests and ES implementations stay trivial. */
    public static ChunkLookup fromFunction(Function<String, Optional<ChunkHit>> function) {
        return function::apply;
    }
}
