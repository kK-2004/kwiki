## ADDED Requirements

### Requirement: Evidence-grounded generation
The system SHALL build generation context from authorized, distinct parent chunks selected by fused child hits and SHALL instruct the answer model to distinguish supported statements from missing knowledge.

#### Scenario: Authorized evidence is available
- **WHEN** retrieval returns authorized child hits that resolve to parent chunks
- **THEN** the generation request contains each distinct bounded parent body once plus page, revision, parent, and matched-child identifiers for citation

#### Scenario: No evidence is available
- **WHEN** scoped retrieval returns no usable chunks
- **THEN** the system does not call the answer model and completes with an explicit no-evidence result

### Requirement: Structured SSE event stream
The system SHALL stream `route`, `rewrite`, `retrieve`, `token`, `citations`, `done`, and `error` events with a stable request identifier and monotonic sequence.

#### Scenario: Answer completes successfully
- **WHEN** routing, retrieval, and generation succeed
- **THEN** progress events precede token events, one citations event follows answer tokens, and one done event terminates the stream

#### Scenario: Failure occurs before completion
- **WHEN** a nonrecoverable planning, retrieval, authorization, or provider error occurs
- **THEN** one sanitized error event terminates the stream and no done event follows it

### Requirement: Resolvable citations
The system SHALL return citations that resolve to an authorized knowledge base, page, published revision, parent chunk, matched child chunk, heading path, character range, and excerpt.

#### Scenario: Reader opens a citation
- **WHEN** the reader selects a citation from an answer
- **THEN** the Wiki opens the cited page version and highlights or scrolls to the corresponding chunk location

#### Scenario: Citation access was revoked
- **WHEN** a citation is requested after the reader loses access to its knowledge base
- **THEN** the system returns no excerpt or restricted page metadata

### Requirement: Cancellation and outbound authorization
The system SHALL cancel downstream streaming work when the client disconnects and SHALL revalidate scope before evidence is sent to an external model or citations are emitted.

#### Scenario: Client disconnects during generation
- **WHEN** the SSE connection closes before generation completes
- **THEN** the application cancels the provider stream and stops emitting events while retaining completed audit metadata

#### Scenario: Scope changes before model call
- **WHEN** authorization scope changes after retrieval but before context leaves the system
- **THEN** generation is aborted and no stale evidence is sent to the provider
