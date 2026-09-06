## ADDED Requirements

### Requirement: Hierarchical Wiki organization
The system SHALL organize content as knowledge bases containing ordered folders and pages, and each page SHALL have a stable identifier independent of its title or location.

#### Scenario: Editor creates a nested page
- **WHEN** an authorized editor creates a page beneath a folder or page
- **THEN** the new page appears at the requested ordered position with a stable identifier

#### Scenario: Editor moves a page
- **WHEN** an authorized editor moves a page to another valid parent in the same knowledge base
- **THEN** the hierarchy and sibling ordering change without changing the page identifier or its revision history

### Requirement: Markdown source with visual editing
The system SHALL persist page content as Markdown and SHALL provide an editing contract that can round-trip supported Markdown through a visual editor without silently losing supported structure.

#### Scenario: Editor saves supported rich content
- **WHEN** an editor saves headings, lists, tables, links, code blocks, and inline formatting in the visual editor
- **THEN** the resulting Markdown preserves those structures and renders them consistently in read mode

#### Scenario: Unsupported content is imported
- **WHEN** imported Markdown contains an unsupported construct
- **THEN** the editor preserves it as source-safe content or reports the unsupported construct before saving

### Requirement: Immutable page revisions
The system SHALL store immutable page revisions and SHALL track separate current draft and current published revisions.

#### Scenario: Editor saves a draft
- **WHEN** an editor saves changes without publishing
- **THEN** a new immutable draft revision is created and readers continue to receive the current published revision

#### Scenario: Editor publishes a draft
- **WHEN** an editor publishes the current draft
- **THEN** that revision becomes the current published revision and an indexing job is recorded atomically

### Requirement: Version restoration
The system SHALL allow an authorized editor to compare revisions and restore an earlier revision by creating a new revision.

#### Scenario: Editor restores an older version
- **WHEN** an editor selects an older revision and confirms restoration
- **THEN** the system creates a new revision with the older content and retains every intervening revision

### Requirement: Links, backlinks, tags, attachments, and sources
The system SHALL track page links by stable page identifier and SHALL expose backlinks, tags, attachments, and source-document metadata for each page.

#### Scenario: Published page links to another page
- **WHEN** a revision containing an internal Wiki link is published
- **THEN** the target page lists the source page as a backlink even if either page is later renamed

#### Scenario: Reader inspects provenance
- **WHEN** a reader opens a page derived from an uploaded document
- **THEN** the page exposes its readable source document and attachment metadata without exposing inaccessible objects

### Requirement: Recoverable page removal
The system SHALL archive pages instead of immediately destroying page content and SHALL prevent hierarchy cycles.

#### Scenario: Editor archives a page
- **WHEN** an authorized editor archives a page
- **THEN** it disappears from normal navigation, its history remains recoverable, and an index-delete job is recorded

#### Scenario: Editor attempts a cyclic move
- **WHEN** an editor attempts to move a page beneath one of its descendants
- **THEN** the system rejects the move without changing the hierarchy
