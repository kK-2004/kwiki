package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;
import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.ResourceAction;
import com.kwiki.wiki.access.ResourceAuthorizationService;
import com.kwiki.wiki.domain.WikiPageRevision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge-bases/{kbId}/pages")
@PreAuthorize("isAuthenticated()")
public class PageController {

    private final PageRevisionService pageRevisions;
    private final com.kwiki.wiki.render.MarkdownPort renderer;
    private final PageLinkService pageLinks;
    private final PageTagService pageTags;
    private final PageProvenanceService pageProvenance;
    private final ResourceAuthorizationService resources;

    public PageController(PageRevisionService pageRevisions,
                          com.kwiki.wiki.render.MarkdownPort renderer,
                          PageLinkService pageLinks,
                          PageTagService pageTags,
                          PageProvenanceService pageProvenance) {
        this(pageRevisions, renderer, pageLinks, pageTags, pageProvenance, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PageController(PageRevisionService pageRevisions,
                          com.kwiki.wiki.render.MarkdownPort renderer,
                          PageLinkService pageLinks,
                          PageTagService pageTags,
                          PageProvenanceService pageProvenance,
                          ResourceAuthorizationService resources) {
        this.pageRevisions = pageRevisions;
        this.renderer = renderer;
        this.pageLinks = pageLinks;
        this.pageTags = pageTags;
        this.pageProvenance = pageProvenance;
        this.resources = resources;
    }

    public record SaveDraftRequest(
            @NotBlank String markdown,
            String changeNote,
            Integer expectedLockVersion) {
    }

    public record RevisionView(long revisionNo, String changeNote, long createdBy,
                               java.time.Instant createdAt, String markdown) {
    }

    public record PublishedView(long revisionNo, String markdown, String html,
                                long createdBy, java.time.Instant createdAt,
                                boolean canEdit, boolean canManage) {
    }

    public record CompareView(RevisionView from, RevisionView to) {
    }

    @GetMapping("/{pageId}")
    ResponseEntity<TransDTO<PublishedView>> read(@AuthenticationPrincipal CurrentUser user,
                                                 @PathVariable long kbId,
                                                 @PathVariable long pageId) {
        WikiPageRevision published = pageRevisions.publishedContent(user, kbId, pageId);
        PublishedView view = new PublishedView(published.getRevisionNo(),
                published.getMarkdown(), renderer.renderToHtml(published.getMarkdown()),
                published.getCreatedBy(), published.getCreatedAt(),
                resources != null && resources.can(user, pageId, ResourceAction.EDIT),
                resources != null && resources.can(user, pageId, ResourceAction.MANAGE));
        String etag = "\"rev-" + published.getRevisionNo() + "\"";
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etag).body(TransDTO.success(view));
    }

    @GetMapping("/{pageId}/draft")
    TransDTO<RevisionView> draft(@AuthenticationPrincipal CurrentUser user,
                                 @PathVariable long kbId,
                                 @PathVariable long pageId) {
        return TransDTO.success(toView(pageRevisions.draft(user, kbId, pageId)));
    }

    @PutMapping("/{pageId}/draft")
    TransDTO<RevisionView> saveDraft(@AuthenticationPrincipal CurrentUser user,
                                     @PathVariable long kbId,
                                     @PathVariable long pageId,
                                     @Valid @RequestBody SaveDraftRequest request) {
        return TransDTO.success(toView(pageRevisions.saveDraft(user, kbId, pageId, request.markdown(),
                request.changeNote(), request.expectedLockVersion())));
    }

    @PostMapping("/{pageId}/publish")
    TransDTO<RevisionView> publish(@AuthenticationPrincipal CurrentUser user,
                                   @PathVariable long kbId,
                                   @PathVariable long pageId) {
        return TransDTO.success(toView(pageRevisions.publish(user, kbId, pageId)));
    }

    @GetMapping("/{pageId}/revisions")
    TransDTO<List<RevisionView>> revisions(@AuthenticationPrincipal CurrentUser user,
                                           @PathVariable long kbId,
                                           @PathVariable long pageId) {
        return TransDTO.success(pageRevisions.revisionHistory(user, kbId, pageId).stream()
                .map(this::toView)
                .toList());
    }

    @GetMapping("/{pageId}/revisions/{fromNo}/compare/{toNo}")
    TransDTO<CompareView> compare(@AuthenticationPrincipal CurrentUser user,
                                  @PathVariable long kbId,
                                  @PathVariable long pageId,
                                  @PathVariable int fromNo,
                                  @PathVariable int toNo) {
        List<WikiPageRevision> history = pageRevisions.revisionHistory(user, kbId, pageId);
        WikiPageRevision from = history.stream()
                .filter(revision -> revision.getRevisionNo() == fromNo)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("revision not found"));
        WikiPageRevision to = history.stream()
                .filter(revision -> revision.getRevisionNo() == toNo)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("revision not found"));
        return TransDTO.success(new CompareView(toView(from), toView(to)));
    }

    @PostMapping("/{pageId}/revisions/{revisionNo}/restore")
    TransDTO<RevisionView> restore(@AuthenticationPrincipal CurrentUser user,
                                   @PathVariable long kbId,
                                   @PathVariable long pageId,
                                   @PathVariable int revisionNo) {
        return TransDTO.success(toView(pageRevisions.restore(user, kbId, pageId, revisionNo)));
    }

    @PostMapping("/{pageId}/archive")
    TransDTO<Void> archive(@AuthenticationPrincipal CurrentUser user,
                           @PathVariable long kbId,
                           @PathVariable long pageId) {
        pageRevisions.archive(user, kbId, pageId);
        return TransDTO.success();
    }

    @GetMapping("/{pageId}/backlinks")
    TransDTO<List<BacklinkView>> backlinks(@AuthenticationPrincipal CurrentUser user,
                                           @PathVariable long kbId,
                                           @PathVariable long pageId) {
        pageRevisions.revisionHistory(user, kbId, pageId);
        return TransDTO.success(pageLinks.backlinks(user, pageId).stream()
                .map(source -> new BacklinkView(source.getId(), source.getUuid(),
                        source.getTitle()))
                .toList());
    }

    public record SetTagsRequest(java.util.List<@jakarta.validation.constraints.NotBlank String> names) {
    }

    @PutMapping("/{pageId}/tags")
    TransDTO<java.util.List<String>> setTags(@AuthenticationPrincipal CurrentUser user,
                                             @PathVariable long kbId,
                                             @PathVariable long pageId,
                                             @RequestBody SetTagsRequest request) {
        return TransDTO.success(pageTags.setTags(user, kbId, pageId, request.names()));
    }

    @GetMapping("/{pageId}/tags")
    TransDTO<java.util.List<String>> tags(@AuthenticationPrincipal CurrentUser user,
                                          @PathVariable long kbId,
                                          @PathVariable long pageId) {
        return TransDTO.success(pageTags.listTags(user, kbId, pageId));
    }

    @GetMapping("/{pageId}/sources")
    TransDTO<java.util.List<PageProvenanceService.SourceView>> sources(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable long kbId,
            @PathVariable long pageId) {
        return TransDTO.success(pageProvenance.readableSources(user, kbId, pageId));
    }

    @GetMapping("/{pageId}/attachments")
    TransDTO<java.util.List<PageProvenanceService.SourceView>> attachments(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable long kbId,
            @PathVariable long pageId) {
        return TransDTO.success(pageProvenance.readableAttachments(user, kbId, pageId));
    }

    public record BacklinkView(Long id, String uuid, String title) {
    }

    private RevisionView toView(WikiPageRevision revision) {
        return new RevisionView(revision.getRevisionNo(), revision.getChangeNote(),
                revision.getCreatedBy(), revision.getCreatedAt(), revision.getMarkdown());
    }
}
