## ADDED Requirements

### Requirement: Low-level model client boundary
The system SHALL implement chat provider access through LangChain4j low-level ChatModel and StreamingChatModel behind kwiki-owned ports. Domain routing, rewriting, retrieval, quality analysis and tool execution MUST NOT depend on AiServices, automatic tool executors, framework RAG pipelines or SDK-specific domain DTOs.

#### Scenario: Model-backed workflow runs
- **WHEN** routing, rewriting, retrieval planning, QA and generation require model access
- **THEN** adapters use low-level model clients and return kwiki-owned results while application nodes retain every control-flow and tool-execution decision

#### Scenario: Framework scope is verified
- **WHEN** architecture checks inspect production dependencies and bean wiring
- **THEN** no AiServices, prebuilt agent or framework automatic tool-execution path is reachable from the workflow

### Requirement: Compatible and isolated client configuration
The system SHALL reuse KWIKI_ANSWER_LLM configuration for chat access, preserve separate Qwen embedding credentials and settings, validate configuration locally without contacting providers at startup, and impose role-specific timeouts bounded by the remaining run deadline. SDK implicit retries SHALL be disabled or explicitly accounted for in the application model-call budget.

#### Scenario: Application starts while the model provider is offline
- **WHEN** valid local configuration exists but the provider is unavailable
- **THEN** model clients are created without remote probing and startup does not depend on provider availability

#### Scenario: A QA request has less remaining time than its role timeout
- **WHEN** a model request begins with three seconds remaining and a fifteen-second role timeout
- **THEN** its effective timeout is no more than three seconds and no hidden retry extends the run deadline

### Requirement: Real rewrite and validated structured responses
The system SHALL provide a model-backed RewriteLlmPort and SHALL validate router, planner and QA outputs against their closed schemas and semantic rules before use. Failed rewrites SHALL preserve the normalized original query; failed QA SHALL never imply sufficient evidence.

#### Scenario: Rewriting is configured and returns a valid decomposition
- **WHEN** the adapter returns one to three nonblank bounded questions
- **THEN** rewriting uses the validated questions and preserves the original user query

#### Scenario: Structured output adds an infrastructure field
- **WHEN** a router or planner response supplies an unrecognized scope, DSL or credential field
- **THEN** the response is rejected and the application chooses its documented safe fallback without executing model-supplied infrastructure instructions

#### Scenario: QA returns invalid JSON
- **WHEN** the QA completion cannot be validated
- **THEN** the adapter returns a typed failure and the workflow does not enter generation on the basis of that response

### Requirement: Cold cancellable answer streaming
The system SHALL adapt SDK streaming into a cold bounded Flux using supported callbacks and completion signals, without emitting null or manually parsing chat-completion SSE. Cancellation SHALL stop further graph work and abort the active provider transport, including cancellation before the first text fragment; late callbacks SHALL be ignored.

#### Scenario: No subscriber exists
- **WHEN** the application constructs an answer publisher without subscribing
- **THEN** no provider request is sent

#### Scenario: Provider completes normally
- **WHEN** partial text events are followed by SDK completion
- **THEN** text is emitted in order and completion occurs exactly once without a null element

#### Scenario: Cancellation precedes the first token
- **WHEN** the client disconnects while the provider has not delivered text
- **THEN** the cancellation token and active transport are cancelled, later handles are cancelled immediately, and no answer or tool work follows

#### Scenario: Provider fails after partial text
- **WHEN** streaming fails after at least one token has been delivered
- **THEN** the application reports a sanitized provider error and does not restart generation or duplicate the delivered text

### Requirement: Trace propagation and evidence boundary
The system SHALL propagate request tracing across model transport and asynchronous callbacks, keep credentials and raw provider content out of default logs, and revalidate authorization immediately before sending retrieved evidence to any model.

#### Scenario: QA runs on a worker thread
- **WHEN** QA sends an evidence-bearing request outside the original request thread
- **THEN** the outbound request retains the captured trace context and uses a freshly checked authorization version

#### Scenario: Access changes before planner follow-up
- **WHEN** tool results are about to be sent to a model after a scope version change
- **THEN** the request is aborted with authorization-changed and stale evidence is not sent

### Requirement: Preserve embedding and retrieval contracts
The system SHALL preserve ChunkEmbeddingPort, the Qwen embedding adapter, configured model and dimensions, and existing domain retrieval interfaces during this change. Framework client integration SHALL NOT require index rebuilding or conversion to EmbeddingStore or RetrievalAugmentor.

#### Scenario: Chat client migration is enabled
- **WHEN** the new chat adapter is used with an existing index
- **THEN** indexing and query embedding continue to use the existing configured Qwen model and vector dimension without rebuilding that index

### Requirement: Verify framework and provider compatibility separately
The implementation SHALL pin resolved dependency versions and record local transport/schema/trace compatibility separately from real-provider acceptance. A documentation-confirmed capability MUST NOT be reported as a successful test of the configured provider.

#### Scenario: Only local protocol tests have run
- **WHEN** MockWebServer streaming and tool-call contracts pass but external credentials are absent
- **THEN** the report marks local compatibility as passed and actual provider acceptance as pending
