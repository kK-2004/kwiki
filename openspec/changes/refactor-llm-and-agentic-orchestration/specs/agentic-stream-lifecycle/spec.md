## ADDED Requirements

### Requirement: Compatible multi-round SSE protocol
The system SHALL preserve POST /api/v1/chat/stream with the query request field and existing route, rewrite, retrieve, token, citations, done and error event fields. It SHALL add tool, quality and retry progress events with bounded non-sensitive node/round/call/status metadata, a stable request ID and a monotonically increasing run-wide sequence.

#### Scenario: HTTP controller encodes an event
- **WHEN** a token event crosses the actual HTTP boundary
- **THEN** the response contains one SSE envelope whose data is parseable JSON with seq, requestId, type and flat text fields, without nested event/data text or replacement of seq by sequence

#### Scenario: QA triggers a second round
- **WHEN** a run retries retrieval after its first QA evaluation
- **THEN** the stream includes quality and retry progress followed by second-round progress without resetting its request ID or sequence

#### Scenario: Progress includes a tool invocation
- **WHEN** es_search starts or finishes
- **THEN** tool events identify tool name, call ID, round and status without exposing raw arguments, full evidence or internal model reasoning

### Requirement: Frontend supports new progress and one-way completion
The frontend SHALL display repeated route/rewrite/retrieve and new tool/quality/retry progress, preserve streamed answer text and citations, ignore unknown nonterminal events, and ignore all events after a terminal frame.

#### Scenario: Client receives an unknown progress type
- **WHEN** a well-formed new nonterminal event is received
- **THEN** the parser safely ignores it without discarding accumulated answer text or terminating the stream

#### Scenario: A late token follows an error
- **WHEN** a terminal error has already been processed
- **THEN** later tokens and progress do not mutate the displayed answer state

### Requirement: Single terminal outcome for every connected run
The system SHALL serialize event emission through one run owner and finalize a connected run exactly once. Successful generation SHALL produce tokens followed by citations and done; direct responses SHALL use the same answer event contract; errors SHALL end with error and no subsequent done.

#### Scenario: Generation completes normally
- **WHEN** answer streaming and final authorization checks succeed
- **THEN** one citations event follows the last token and exactly one done terminates the run

#### Scenario: Clarification or insufficient evidence is returned
- **WHEN** the graph chooses a non-generation terminal response
- **THEN** the stream exposes its user-facing message and explicit outcome, keeps the existing noEvidence/message semantics where applicable, emits empty citations when appropriate and ends once

#### Scenario: Provider failure races a completion callback
- **WHEN** error and completion signals arrive concurrently
- **THEN** the atomic terminal guard accepts one terminal outcome and suppresses the losing callback and duplicate audit

### Requirement: Whole-run cancellation and bounded buffering
The system SHALL delay execution until subscription, maintain bounded event buffering, and propagate cancellation and deadline expiration to graph nodes and all active model, embedding and ES operations. Blocking work SHALL NOT run on provider I/O callbacks, and cancelled runs SHALL release worker permits and buffers.

#### Scenario: Client disconnects during ES retrieval
- **WHEN** the SSE subscriber cancels while an ES request is active
- **THEN** the active work receives cancellation, subsequent QA/generation are suppressed, resources are released and a cancelled audit outcome is recorded

#### Scenario: Client disconnects during QA
- **WHEN** a quality model request is pending at cancellation
- **THEN** its transport is cancelled and no retrieval retry or answer generation begins

#### Scenario: Slow client exhausts the event buffer
- **WHEN** the configured stream buffer is full
- **THEN** upstream work is cancelled and the connected client receives a sanitized overflow error if the connection remains writable, without unbounded memory growth

### Requirement: Authorized and resolvable final citations
The system SHALL revalidate scope before evidence-bearing model requests and citation emission. Citations SHALL resolve to retained authorized parent/child/revision evidence actually referenced by the answer or directly returned excerpt; unknown model citation identifiers SHALL never be replaced with invented references.

#### Scenario: Scope changes after answer tokens begin
- **WHEN** authorization becomes stale before citations are emitted
- **THEN** subsequent work stops, no stale citations are sent and the connected run ends with authorization-changed

#### Scenario: Answer contains an unknown citation marker
- **WHEN** generation references an ID absent from the final context
- **THEN** the run reports answer-validation-failed, emits no fabricated citation and the UI treats the partially received answer as incomplete

#### Scenario: User opens a returned citation
- **WHEN** the citation is resolved under the user's current permissions
- **THEN** it identifies the correct resource, published revision, parent/child chunk, heading and character location, or denies access without exposing restricted metadata

### Requirement: Auditable finalization without schema changes
The system SHALL attempt audit persistence once per terminal or cancelled run using the captured request/trace ID and existing chat/request_trace storage. It SHALL record final outcome, node summaries, budgets, degradation reasons and tool schema versions without persisting raw graph snapshots or credentials. Audit failure SHALL NOT produce a second terminal event.

#### Scenario: Audit executes on an asynchronous worker
- **WHEN** finalization occurs outside the initial request thread
- **THEN** persisted correlation uses the captured run trace ID rather than a missing worker MDC value

#### Scenario: Database persistence fails after completion
- **WHEN** audit persistence throws after the response outcome is selected
- **THEN** the selected outcome is retained, no second terminal event is emitted and an audit failure metric is recorded
