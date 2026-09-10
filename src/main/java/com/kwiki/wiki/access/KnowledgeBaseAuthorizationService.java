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
 * 面向 OWNER/EDITOR/VIEWER 角色的表驱动成员关系策略。该矩阵是角色/动作决策的
 * 唯一权威来源；非成员被拒绝所有动作（默认拒绝）。平台管理员绕过成员关系，
 * 但检索的作用域过滤仍会参考基于成员关系的作用域。
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
        matrix.put(KnowledgeBaseRole.ADMIN, Set.of(
                WikiAction.READ_PAGE,
                WikiAction.VIEW_REVISION_HISTORY,
                WikiAction.SEARCH_AND_RETRIEVE,
                WikiAction.CREATE_PAGE,
                WikiAction.EDIT_PAGE,
                WikiAction.ARCHIVE_PAGE,
                WikiAction.RESTORE_REVISION,
                WikiAction.UPLOAD_ATTACHMENT,
                WikiAction.MANAGE_MEMBERS));
        return java.util.Collections.unmodifiableMap(matrix);
    }

    /** 策略核心：{@code role} 是否允许 {@code action}？空角色（非成员）拒绝一切。 */
    public boolean isAllowed(KnowledgeBaseRole role, WikiAction action) {
        if (role == null) {
            return false;
        }
        return ALLOWED.getOrDefault(role, Set.of()).contains(action);
    }

    /** 带管理员绕过的角色解析；无成员关系的用户解析为空。 */
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

    /** 抛出经脱敏的拒绝访问异常，由安全层消费。 */
    public void require(CurrentUser user, long kbId, WikiAction action) {
        if (!can(user, kbId, action)) {
            throw new AccessDeniedException("access denied");
        }
    }
}
