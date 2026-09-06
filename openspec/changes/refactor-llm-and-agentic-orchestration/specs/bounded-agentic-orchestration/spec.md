## ADDED Requirements

### Requirement: Explicit request-scoped state graph
The system SHALL execute each answer through LangGraph4j core with explicit route, rewrite/strategy, retrieval planner, application tool dispatcher, evidence assembly, quality analysis and response nodes. Every request SHALL have isolated state, trusted server context and bounded state reducers; the graph SHALL NOT delegate control flow to a framework tool agent.

#### Scenario: Evidence is sufficient on the first attempt
- **WHEN** a knowledge query yields authorized relevant evidence and a valid sufficient QA decision
- **THEN** the graph traverses route, rewrite/strategy, planner, validated manual tool execution, evidence assembly, QA and generation before its single terminal outcome

#### Scenario: Two requests run concurrently
- **WHEN** different users submit questions concurrently
- **THEN** evidence, tool results, scope, counters and cancellation are isolated per run even when the compiled graph is shared

### Requirement: Respect routing and safe direct responses
The system SHALL preserve versioned rule-first routing and its scoped knowledge fallback. Only server-allowlisted non-knowledge intents SHALL bypass retrieval; a model-provided needsRetrieval=false flag alone MUST NOT permit ungrounded knowledge answers.

#### Scenario: Deterministic greeting intent matches
- **WHEN** a query matches the server's greeting or help whitelist
- **THEN** the system returns the corresponding template without ES, embedding or answer-model calls

#### Scenario: Router requests a direct answer for an enterprise fact
- **WHEN** the model marks an enterprise knowledge query as not requiring retrieval but it does not match the safe whitelist
- **THEN** the graph falls back to authorized knowledge retrieval

#### Scenario: Rules match exactly one terminal knowledge intent
- **WHEN** deterministic routing resolves an unambiguous intent
- **THEN** the route includes rule provenance without calling the router model

### Requirement: Bounded intent-aware query and strategy planning
The system SHALL support NONE, CONVERSATIONAL, EXPANSION and DECOMPOSITION rewrite modes, preserve the original query, and produce at most three normalized nonblank unique subqueries per round. Strategy selection SHALL be limited to BM25, VECTOR and HYBRID and SHALL use QA gaps on subsequent rounds.

#### Scenario: Query rewriting fails
- **WHEN** rewriting times out or emits empty, oversized or invalid output
- **THEN** the graph uses the normalized original query and records a fallback reason

#### Scenario: Follow-up query has no trusted history
- **WHEN** a query contains a conversation reference but the request has no authorized session history
- **THEN** the graph does not invent history or read another session and either retrieves with the original query or returns a clarification for a missing essential referent

### Requirement: Independent evidence quality analyzer
The system SHALL analyze aggregated authorized evidence in a dedicated QA node before knowledge generation. The analyzer SHALL check evidence validity deterministically and validate model decisions for relevance, requested-aspect coverage and conflicts. Search scores MUST NOT be interpreted as answer correctness probabilities.

#### Scenario: Nonempty evidence omits a requested aspect
- **WHEN** retrieved content answers only one part of a compound question
- **THEN** QA identifies the missing aspect and selects bounded retry or insufficient evidence rather than accepting the result solely because chunks were returned

#### Scenario: QA claims nonexistent support
- **WHEN** a QA output references an evidence ID absent from the current authorized evidence set
- **THEN** the decision is invalid and cannot permit generation

#### Scenario: A retrieved document contains instructions to bypass evaluation
- **WHEN** evidence text requests skipping QA or running a different tool
- **THEN** it remains untrusted source material and cannot change the graph, allowed tools or authorization context

### Requirement: Validated QA response decisions
The system SHALL accept only the closed QA decision fields action, sufficient, supportedEvidenceIds, missingAspects, reasonCode, suggestedQueries and returnKind with bounded values. GENERATE SHALL require sufficient=true and nonempty current support; RETRY SHALL require remaining budget and a new allowed plan; RETURN SHALL produce an explicit clarification, insufficient-evidence result or requested supported excerpts.

#### Scenario: QA approves generation
- **WHEN** evidence is sufficient, support IDs are valid and returnKind is NONE
- **THEN** the graph revalidates authorization and calls the answer model with bounded evidence

#### Scenario: User explicitly requests original passages
- **WHEN** QA confirms the passages satisfy the request and selects RETURN with EVIDENCE
- **THEN** the system directly formats authorized excerpts with real citations and does not call the answer model

#### Scenario: Search returns no matches
- **WHEN** a successful retrieval returns zero evidence and no new useful attempt remains
- **THEN** the graph completes with explicit insufficient evidence and does not call the answer model

### Requirement: Finite feedback loop and no-progress detection
The system SHALL limit retrieval rounds to a configured maximum defaulting to three including the initial round, retain valid prior evidence, and reject repeated normalized query/strategy/topK/scope-version attempt signatures. From the second round onward, no new evidence and no coverage improvement relative to the preceding round SHALL terminate further retrieval. An empty first round SHALL permit a distinct recovery plan within budget. Budget exhaustion SHALL NOT convert insufficient evidence into approval.

#### Scenario: QA requests a useful second search
- **WHEN** the first round misses a subquestion and proposes a distinct allowed plan within budget
- **THEN** the graph returns through strategy/rewrite and validated tool execution, merges valid evidence and runs QA again

#### Scenario: Planner repeats the same attempt
- **WHEN** the proposed queries, strategy, topK and scope version match an earlier attempt
- **THEN** no duplicate retrieval executes and the graph returns a structured no-progress or insufficient result

#### Scenario: Maximum rounds are reached with insufficient evidence
- **WHEN** the third default retrieval round still cannot support the requested answer
- **THEN** the system returns insufficient evidence without entering generation

#### Scenario: Initial search misses but a new strategy is available
- **WHEN** the first successful search has zero hits and a distinct allowed recovery plan remains
- **THEN** the graph can perform a second search within budget rather than treating the empty initial baseline as repeated no-progress

### Requirement: Global execution limits and cancellation
The system SHALL enforce one run deadline and limits for graph steps, all model request attempts, tool execution attempts, per-round tool calls and schema repairs. Default limits SHALL be 180 seconds, 64 node executions, 16 model requests, nine tool executions, three tool calls per round and one schema repair per round. Node-local timeouts and retries SHALL NOT reset or exceed these limits.

#### Scenario: Tool schema repair is attempted
- **WHEN** the planner is asked once to correct invalid arguments
- **THEN** the additional model request consumes the global model budget without incrementing the retrieval-round counter

#### Scenario: Run deadline expires in QA
- **WHEN** the global deadline expires while QA is pending
- **THEN** the QA request and graph are cancelled and the connected client receives one timeout error with no generation call

#### Scenario: Caller disconnects during retrieval planning
- **WHEN** cancellation is signalled before tool execution
- **THEN** planning is cancelled and neither the tool nor subsequent nodes execute
