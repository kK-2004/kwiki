## ADDED Requirements

### Requirement: Knowledge-base member roles
The system SHALL authorize knowledge-base operations using `OWNER`, `EDITOR`, and `VIEWER` roles, with owners managing membership, editors changing content, and viewers reading published content.

#### Scenario: Viewer attempts to edit
- **WHEN** a viewer submits a page mutation
- **THEN** the system rejects the operation and leaves content unchanged

#### Scenario: Owner changes membership
- **WHEN** an owner grants or revokes a member role
- **THEN** the new authorization state applies to subsequent requests and the scope version changes

### Requirement: Server-side scope enforcement
The system MUST enforce authorization on navigation, page, attachment, search, retrieval, citation, and history endpoints regardless of which controls the client displays.

#### Scenario: User requests an inaccessible page directly
- **WHEN** an authenticated user calls a page endpoint for a knowledge base outside the user's scope
- **THEN** the server returns no page content or metadata that confirms restricted content

#### Scenario: User searches multiple knowledge bases
- **WHEN** a search spans more than one knowledge base
- **THEN** every result belongs to the immutable authorization scope resolved for that request

### Requirement: Equivalent retrieval filters
The system SHALL apply the same authorization filter before TopK selection in both BM25 and vector branches.

#### Scenario: Restricted chunk scores highly in both branches
- **WHEN** a chunk outside the user's scope would otherwise rank in either branch
- **THEN** it is filtered before branch ranking and contributes nothing to RRF

### Requirement: Scope-version outbound guard
The system SHALL detect membership changes during long-running retrieval or generation and SHALL prevent evidence resolved under a stale scope from leaving the system.

#### Scenario: Access is revoked during answer generation
- **WHEN** the user's scope version changes after retrieval and before evidence or citations are sent externally
- **THEN** the request is stopped and no stale-scope evidence or citation content is emitted
