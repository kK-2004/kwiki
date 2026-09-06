package com.kwiki.wiki.access;

import com.kwiki.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Table-driven policy tests covering every role/action combination, non-member
 * denial, admin bypass, and the fail-closed behavior when membership storage is
 * unavailable.
 */
class KnowledgeBaseAuthorizationServiceTest {

    private static final Set<WikiAction> VIEWER_CAN = Set.of(
            WikiAction.READ_PAGE, WikiAction.VIEW_REVISION_HISTORY, WikiAction.SEARCH_AND_RETRIEVE);

    private static final Set<WikiAction> EDITOR_CAN = Set.of(
            WikiAction.READ_PAGE, WikiAction.VIEW_REVISION_HISTORY, WikiAction.SEARCH_AND_RETRIEVE,
            WikiAction.CREATE_PAGE, WikiAction.EDIT_PAGE, WikiAction.ARCHIVE_PAGE,
            WikiAction.RESTORE_REVISION, WikiAction.UPLOAD_ATTACHMENT);

    private static final Set<WikiAction> OWNER_CAN = Set.of(
            WikiAction.READ_PAGE, WikiAction.VIEW_REVISION_HISTORY, WikiAction.SEARCH_AND_RETRIEVE,
            WikiAction.CREATE_PAGE, WikiAction.EDIT_PAGE, WikiAction.ARCHIVE_PAGE,
            WikiAction.RESTORE_REVISION, WikiAction.UPLOAD_ATTACHMENT,
            WikiAction.MANAGE_MEMBERS, WikiAction.UPDATE_KNOWLEDGE_BASE,
            WikiAction.ARCHIVE_KNOWLEDGE_BASE);

    private static final Map<KnowledgeBaseRole, Set<WikiAction>> EXPECTED = Map.of(
            KnowledgeBaseRole.VIEWER, VIEWER_CAN,
            KnowledgeBaseRole.EDITOR, EDITOR_CAN,
            KnowledgeBaseRole.OWNER, OWNER_CAN);

    private final KnowledgeBaseAuthorizationService service =
            new KnowledgeBaseAuthorizationService(stubLookup(Optional.empty()));

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MembershipLookup> stubLookup(Optional<KnowledgeBaseRole> role) {
        return new ObjectProvider<>() {
            @Override
            public MembershipLookup getObject() {
                return (kbId, userId) -> role;
            }

            @Override
            public MembershipLookup getIfAvailable() {
                return (kbId, userId) -> role;
            }
        };
    }

    @ParameterizedTest
    @EnumSource(KnowledgeBaseRole.class)
    void everyRoleActionCombinationMatchesTheExpectedMatrix(KnowledgeBaseRole role) {
        for (WikiAction action : WikiAction.values()) {
            boolean expected = EXPECTED.get(role).contains(action);
            assertThat(service.isAllowed(role, action))
                    .as("%s x %s", role, action)
                    .isEqualTo(expected);
        }
    }

    @Test
    void nullRoleIsDeniedEveryAction() {
        for (WikiAction action : WikiAction.values()) {
            assertThat(service.isAllowed(null, action))
                    .as("non-member must not perform %s", action)
                    .isFalse();
        }
    }

    @Test
    void viewerMutationIsRejectedWithoutSideEffects() {
        CurrentUser viewer = new CurrentUser(2L, "viewer", false);
        assertThat(service.can(viewer, 1L, WikiAction.EDIT_PAGE)).isFalse();
        assertThatThrownBy(() -> service.require(viewer, 1L, WikiAction.EDIT_PAGE))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
                .hasMessage("access denied");
    }

    @Test
    void memberRoleIsResolvedFromLookup() {
        KnowledgeBaseAuthorizationService memberService =
                new KnowledgeBaseAuthorizationService(stubLookup(Optional.of(KnowledgeBaseRole.EDITOR)));
        CurrentUser editor = new CurrentUser(3L, "editor", false);
        assertThat(memberService.resolveRole(1L, editor)).contains(KnowledgeBaseRole.EDITOR);
        assertThat(memberService.can(editor, 1L, WikiAction.EDIT_PAGE)).isTrue();
        assertThat(memberService.can(editor, 1L, WikiAction.MANAGE_MEMBERS)).isFalse();
        assertThatCode(() -> memberService.require(editor, 1L, WikiAction.EDIT_PAGE))
                .doesNotThrowAnyException();
    }

    @Test
    void adminBypassesMembership() {
        CurrentUser admin = new CurrentUser(1L, "root", true);
        assertThat(service.resolveRole(1L, admin)).contains(KnowledgeBaseRole.OWNER);
        assertThat(service.can(admin, 1L, WikiAction.MANAGE_MEMBERS)).isTrue();
    }

    @Test
    void missingLookupFailsClosed() {
        KnowledgeBaseAuthorizationService noLookupService =
                new KnowledgeBaseAuthorizationService(new ObjectProvider<>() {
                    @Override
                    public MembershipLookup getIfAvailable() {
                        return null;
                    }
                });
        CurrentUser user = new CurrentUser(9L, "someone", false);
        assertThat(noLookupService.can(user, 1L, WikiAction.READ_PAGE)).isFalse();
        assertThatThrownBy(() -> noLookupService.require(user, 1L, WikiAction.READ_PAGE))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
