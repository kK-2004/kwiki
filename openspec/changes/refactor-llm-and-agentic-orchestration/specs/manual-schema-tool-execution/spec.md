## ADDED Requirements

### Requirement: Application-owned discoverable tool schemas
The system SHALL define versioned tool names, descriptions, canonical input/output JSON Schemas and application handlers in a kwiki ToolRegistry. The application SHALL be able to retrieve the canonical schema by tool name and version at invocation time; provider ToolSpecifications SHALL be derived from that same definition.

#### Scenario: Retrieval planner receives the tool catalog
- **WHEN** the application builds an ES retrieval-planning request
- **THEN** it supplies the allowed es_search specification derived from the same versioned schema that the dispatcher will read and validate

#### Scenario: Provider supports only a schema subset
- **WHEN** the provider specification omits an unsupported validation keyword
- **THEN** server validation still enforces the complete canonical schema and the projection is covered by compatibility tests

### Requirement: Complete model-selected calls without automatic execution
The system SHALL let the retrieval model select an allowed tool and its arguments through low-level native tool calling. Only an explicitly configured JSON-envelope compatibility mode SHALL accept simulated calls, and both modes MUST use the same application validator and dispatcher. No AiServices, automatic ToolNode, annotation-driven executor or provider-hosted retrieval tool SHALL execute knowledge retrieval.

#### Scenario: Native tool call is returned
- **WHEN** a complete model response contains es_search with a call ID and JSON arguments
- **THEN** the adapter returns a kwiki ToolCall to the application without executing a handler

#### Scenario: Model returns prose instead of a required call
- **WHEN** retrieval planning returns a natural-language answer and no valid call
- **THEN** the system treats it as an invalid plan and never uses it as a retrieval result or grounded answer

#### Scenario: Provider streams incomplete arguments
- **WHEN** only a partial tool-call argument fragment has arrived
- **THEN** the application executes no tool until the complete request has been assembled and validated

### Requirement: Schema and semantic validation before dispatch
The system SHALL validate the entire proposed batch before any handler is executed: allowed tool name, call identity, strict JSON parsing, input size/depth, required fields, types, enums, ranges and additionalProperties=false. It SHALL then validate nonblank normalized queries, aggregate subquery limits and configured runtime budgets. Duplicate JSON keys and duplicate call IDs within a batch MUST be rejected.

#### Scenario: Valid ES search arguments are supplied
- **WHEN** es_search contains one to three unique nonblank bounded queries, a permitted strategy and integer topK within configured limits
- **THEN** the dispatcher retrieves the registered schema, validates the batch and decodes typed application arguments before invoking its handler

#### Scenario: Arguments include a user-selected scope
- **WHEN** a call contains userId, kbIds, scope, index, DSL or any unrecognized field
- **THEN** validation rejects the whole batch before any embedding or ES operation occurs

#### Scenario: A syntactically valid number exceeds the runtime budget
- **WHEN** topK is 100 and the configured child-branch limit is 50
- **THEN** the call is rejected even though the hard schema maximum is 200

#### Scenario: One call in a batch is invalid
- **WHEN** a batch contains two valid calls and one invalid call
- **THEN** no call in that batch is executed and the returned validation result identifies the invalid call without exposing protected data

#### Scenario: Batched calls collectively exceed the query limit
- **WHEN** individually valid calls introduce more than three distinct effective queries in one retrieval round
- **THEN** the batch is rejected before execution

### Requirement: Explicit authorized application dispatch
The system SHALL manually dispatch validated calls through a registered application handler using trusted server identity, authorization scope, current scope version, run deadline and cancellation token. Model inputs SHALL NOT populate or override trusted execution context. Scope SHALL be validated before and after a retrieval operation and before tool evidence leaves the system.

#### Scenario: Authorized ES handler is invoked
- **WHEN** a validated es_search call enters the dispatcher
- **THEN** the handler receives only server-resolved scope and executes TopK searches with that scope applied before ranking

#### Scenario: Authorization changes while the tool runs
- **WHEN** the scope version no longer matches after retrieval
- **THEN** the result is discarded, the graph terminates with authorization-changed and no stale result reaches QA, generation or citations

### Requirement: Bounded repair and validated fallback
The system SHALL return structured validation errors correlated to calls and allow at most one planner repair per round within the global model budget. After repair exhaustion it SHALL create at most one deterministic authorized HYBRID fallback call through the same schema validator and dispatcher. A fallback validation failure SHALL terminate without re-entering repair or fallback.

#### Scenario: Planner corrects invalid arguments
- **WHEN** the first call has an invalid enum and the permitted repair returns a valid strategy
- **THEN** only the corrected call executes and both planning attempts are counted

#### Scenario: Repeated invalid calls exhaust repair
- **WHEN** the repaired plan is still invalid and retrieval budget remains
- **THEN** one server-constructed safe call is validated and executed with a recorded fallback reason

#### Scenario: Server fallback is invalid
- **WHEN** the deterministic fallback fails its own schema or budget checks
- **THEN** no handler executes and the workflow emits one internal or budget-related outcome without looping

### Requirement: Correlated validated results and deduplication
The system SHALL represent tool outcomes with call ID, status, evidence references, degradations, error code and statistics, validate the output schema, and close every executed call with a success or error result. When a model continuation uses tool history, the application SHALL supply the original assistant tool-call message and exactly one corresponding result per call. Completed calls SHALL be deduplicated per run.

#### Scenario: Two calls finish with different outcomes
- **WHEN** one call succeeds and another times out
- **THEN** each result retains its own call ID and status and the model continuation cannot mistake the timeout for successful evidence

#### Scenario: Complete callback and final response expose the same call
- **WHEN** the same call ID and arguments are delivered twice in a run
- **THEN** the handler executes at most once and the completed result is reused or the duplicate event is ignored

#### Scenario: A completed call ID is reused with different arguments
- **WHEN** a later event changes arguments for an existing call ID
- **THEN** the system reports a protocol error and does not execute the conflicting request
