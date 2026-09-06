## ADDED Requirements

### Requirement: Versioned rule-first intent recognition
The system SHALL normalize each query and evaluate a versioned deterministic rule set before calling an LLM router.

#### Scenario: One terminal rule intent matches
- **WHEN** a normalized query matches rules that resolve to exactly one terminal intent
- **THEN** the system returns that intent with rule version and matched rule identifiers without calling the router LLM

#### Scenario: Conflicting rule intents match
- **WHEN** matching rules resolve to more than one terminal intent
- **THEN** the system sends the normalized query and non-sensitive match trace to the router LLM

### Requirement: Schema-constrained LLM routing
The system SHALL call the router LLM only for no-match, conflict, or compound-query cases and SHALL accept only a validated schema containing intent, retrieval need, rewrite mode, bounded subqueries, and confidence.

#### Scenario: Router returns a valid decision
- **WHEN** the LLM output conforms to the routing schema and allowed enum values
- **THEN** the system records an LLM-sourced route and continues with the validated plan

#### Scenario: Router attempts to supply infrastructure instructions
- **WHEN** the LLM output contains ES DSL, authorization scope, credentials, or an unrecognized field
- **THEN** schema validation rejects the output and the fallback route is used

### Requirement: Safe route fallback
The system SHALL fall back to scoped `KNOWLEDGE_QA` retrieval when LLM routing times out, fails, or produces invalid output.

#### Scenario: Router is unavailable
- **WHEN** the router call exceeds its deadline or returns a provider error
- **THEN** the request continues with scoped knowledge retrieval and records the fallback reason

### Requirement: Intent-aware query rewriting
The system SHALL support `NONE`, `CONVERSATIONAL`, `EXPANSION`, and `DECOMPOSITION` rewrite modes while preserving the original query.

#### Scenario: Follow-up query contains a conversation reference
- **WHEN** a query depends on prior chat context and the plan selects `CONVERSATIONAL`
- **THEN** the effective query is self-contained and the original query remains available for audit and display

#### Scenario: Compound question is decomposed
- **WHEN** the plan selects `DECOMPOSITION`
- **THEN** the system produces between one and three nonblank subqueries without adding authorization or infrastructure instructions

#### Scenario: Rewrite output is blank or unsafe
- **WHEN** rewriting returns blank content, exceeds limits, or violates its output schema
- **THEN** the system uses the original normalized query and records a rewrite fallback

### Requirement: Route and rewrite observability
The system SHALL record route source, rule version, intent, rewrite mode, effective query count, latency, and fallback reason without logging full sensitive content by default.

#### Scenario: Agentic planning completes
- **WHEN** routing and rewriting finish
- **THEN** the request trace contains the planning metadata required to diagnose the chosen path
