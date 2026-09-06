package com.kwiki.wiki.access;

import com.kwiki.security.CurrentUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Table-driven membership policy for OWNER/EDITOR/VIEWER roles. The matrix is the
 * single source of truth for role/action decisions; non-members are denied every
 * action (fail closed). Platform admins bypass membership, but scope filtering for
 * retrieval still consults membership-based scopes.
 */
@Service
public class KnowledgeBaseAuthorizationService {

    private static final Map<KnowledgeBaseRole, Set<WikiAction>> ALLOWED = buildMatrix();

    private final MembershipLookup membershipLookup;

    public KnowledgeBaseAuthorizationService(ObjectProvider<MembershipLookup> membershipLookup) {
        this.membershipLookup = membershipLookup.getIfAvailable();
    }

    private static Map<KnowledgeBaseRole, Set<WikiAction>> buildMatrix() {
        Map<KnowledgeBaseRole, Set<WikiAction>> matrix = new EnumMap<>(KnowledgeBaseRole.class);
        matrix.put(KnowledgeBaseRole.VIEWER, Set.of(
                WikiAction.READ_PAGE,
                WikiAction.VIEW_REVISION_HISTORY,
                WikiAction.SEARCH_AND_RETRIEVE));
        matrix.put(KnowledgeBaseRole.EDITOR, Set.of(
                WikiAction.READ_PAGE,
                WikiAction.VIEW_REVISION_HISTORY,
                WikiAction.SEARCH_AND_RETRIEVE,
                WikiAction.CREATE_PAGE,
                WikiAction.EDIT_PAGE,
                WikiAction.ARCHIVE_PAGE,
                WikiAction.RESTORE_REVISION,
                WikiAction.UPLOAD_ATTACHMENT));
        matrix.put(KnowledgeBaseRole.OWNER, Set.of(
                WikiAction.READ_PAGE,
                WikiAction.VIEW_REVISION_HISTORY,
                WikiAction.SEARCH_AND_RETRIEVE,
                WikiAction.CREATE_PAGE,
                WikiAction.EDIT_PAGE,
                WikiAction.ARCHIVE_PAGE,
                WikiAction.RESTORE_REVISION,
                WikiAction.UPLOAD_ATTACHMENT,
                WikiAction.MANAGE_MEMBERS,
                WikiAction.UPDATE_KNOWLEDGE_BASE,
                WikiAction.ARCHIVE_KNOWLEDGE_BASE));
        return java.util.Collections.unmodifiableMap(matrix);
    }

    /** Policy core: is {@code action} allowed for {@code role}? Null role (non-member) denies all. */
    public boolean isAllowed(KnowledgeBaseRole role, WikiAction action) {
        if (role == null) {
            return false;
        }
        return ALLOWED.getOrDefault(role, Set.of()).contains(action);
    }

    /** Role resolution with admin bypass; users without membership resolve empty. */
    public Optional<KnowledgeBaseRole> resolveRole(long kbId, CurrentUser user) {
        if (user.admin()) {
            return Optional.of(KnowledgeBaseRole.OWNER);
        }
        if (membershipLookup == null) {
            return Optional.empty();
        }
        return membershipLookup.findRole(kbId, user.id());
    }

    public boolean can(CurrentUser user, long kbId, WikiAction action) {
        return isAllowed(resolveRole(kbId, user).orElse(null), action);
    }

    /** Throws a sanitized access-denied failure consumed by the security layer. */
    public void require(CurrentUser user, long kbId, WikiAction action) {
        if (!can(user, kbId, action)) {
            throw new AccessDeniedException("access denied");
        }
    }
}
