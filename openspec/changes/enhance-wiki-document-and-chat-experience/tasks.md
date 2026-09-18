## 1. Contracts and Test Baseline

- [x] 1.1 Inventory the current page DTO, `source_document`/attachment association, citation route flow, conversation-store switching rules, and browser test utilities; record the exact affected contracts before editing implementation.
- [x] 1.2 Add failing frontend tests for reference focus success, async page replacement, ambiguous/missing excerpts, visible highlight cleanup, and the no-Custom-Highlight fallback.
- [x] 1.3 Add failing frontend tests for PDF/DOCX source-tab visibility, Markdown/no-source omission, tab state, watermark presence, preview failure, and resource cleanup.
- [x] 1.4 Add failing frontend tests for floating new/history actions, list states, in-place historical selection, Escape/focus behavior, and no duplicate run or stream during navigation.
- [x] 1.5 Add failing frontend tests for independent message/thinking follow-tail pause/resume, non-answer height changes, expand/collapse animation state, and reduced-motion behavior.

## 2. Authorized Source Document API

- [x] 2.1 Extend the page-read response with an optional source-document summary derived from `source_document` and the actual attachment MIME/signature, without returning a persistent CDN URL.
- [x] 2.2 Implement a page-scoped source preview endpoint that rechecks page read authorization, source-to-page association, attachment state, supported PDF/DOCX type, and authorization again before returning protected bytes or a short-lived URL.
- [x] 2.3 Add backend API/service tests for valid PDF and DOCX sources, Markdown/no-source pages, foreign and archived pages, mismatched attachments, revoked access, invalid MIME/signature, and responses that contain no permanent public URL.
- [x] 2.4 Update the frontend page API types/store hydration for optional source metadata while preserving compatibility with responses that omit the field.

## 3. Source Preview UI and Watermark

- [x] 3.1 Add and lock compatible PDF.js and DOCX browser-preview dependencies, configure their workers/chunks for lazy loading, and verify production bundling keeps them out of the initial reader bundle.
- [x] 3.2 Create a common source-preview component with authenticated Blob acquisition, loading/error/retry states, cancellation, stale-request protection, and deterministic Object URL/worker/observer cleanup.
- [x] 3.3 Implement the PDF.js adapter with bounded/lazy page rendering and the DOCX adapter with bounded rendering, including clear unsupported, corrupt, and oversized-file states.
- [x] 3.4 Add a non-interactive repeated watermark layer containing current user identity and view time that remains visible across scroll, resize, zoom/page changes, and print styling.
- [x] 3.5 Integrate format-specific source and parsed-text tabs into the Wiki reader only for valid PDF/DOCX imports, preserve parsed-text reading state across tab switches, and keep Markdown/ordinary pages unchanged.

## 4. Reliable Reference Focus

- [x] 4.1 Refactor citation navigation into a cancellable target object keyed by page, revision, and child chunk so route changes and component teardown invalidate stale resolution work.
- [x] 4.2 Replace global content lookup with a reader-owned element reference and a post-render readiness handshake that also accounts for asynchronous Markdown/media mounting.
- [x] 4.3 Upgrade `locateChunk` to normalize rendered whitespace, map verified excerpt offsets back to a DOM Range, prefer the valid match nearest `charStart`, and reject ambiguous or unverifiable matches.
- [x] 4.4 Add visible `::highlight(kwiki-citation)` styling plus a temporary `<mark>` fallback, and clean both paths on timeout, subsequent focus, target change, and unmount.
- [x] 4.5 Consume the chunk query only after a final success/error result, scroll successful targets into view, and show safe excerpts/status for changed, missing, or unauthorized references without false highlighting.

## 5. Floating Conversation Navigation

- [x] 5.1 Add accessible “新建会话” and “历史会话” controls to the expanded launcher, with an internal messages/history panel state that does not mount the route-level conversation page.
- [x] 5.2 Reuse the conversation store to create a blank conversation and to load, paginate, retry, and render the owner's historical session summaries inside the floating component.
- [x] 5.3 Implement in-place historical session selection and return to messages while applying the same loading, evidence-redaction, and continuation behavior as the full conversation page.
- [x] 5.4 Enforce existing active-run switching rules in the UI so history browsing never cancels, duplicates, or resubscribes a run; disable unsafe selection with a clear explanation and preserve expand-to-page continuity.
- [x] 5.5 Implement contained scrolling, focus management, responsive layout, and Escape ordering so the history panel closes before the floating window minimizes.

## 6. Follow-Tail State Machine and Thinking Animation

- [x] 6.1 Extract a reusable per-container follow-tail controller with a 48px bottom threshold, explicit `FOLLOWING`/`PAUSED` state, user wheel/touch/keyboard intent handling, and frame-coalesced programmatic scrolling.
- [x] 6.2 Attach the controller to the message list with a bottom sentinel and `ResizeObserver` so tokens, retrieval steps, citations, history, media, and layout changes follow only while the user remains at the bottom.
- [x] 6.3 Attach an independent controller to expanded thinking content and prevent nested scroll events or programmatic writes from changing the outer message area's state.
- [x] 6.4 Replace abrupt thinking visibility changes with a height/opacity transition that supports streaming growth, preserves scroll/follow state across collapse and reopen, and retains correct ARIA relationships.
- [x] 6.5 Add `prefers-reduced-motion` handling that removes or shortens only the visual transition without changing content, focus, scrolling, or accessibility semantics.

## 7. Integration Verification and Documentation

- [x] 7.1 Run frontend unit/component tests and production build, fixing leaks, observer warnings, worker asset paths, focus regressions, and TypeScript errors.
- [x] 7.2 Run backend unit/integration tests for page/source authorization and the standard Maven verification relevant to changed modules.
- [x] 7.3 Add browser end-to-end coverage for reference-list-to-highlight navigation, PDF/DOCX/Markdown page variants, watermarked preview, floating history continuation, and user-controlled streaming scroll restoration.
- [x] 7.4 Manually verify desktop and narrow viewports with keyboard-only navigation, reduced motion, long PDF/DOCX files, rapid page/tab switching, active streaming, revoked authorization, and browsers with/without Custom Highlight API.
- [x] 7.5 Update workspace/API documentation with source preview security, watermark limitations, supported formats/size bounds, citation fallback behavior, and follow-tail interaction rules.
