## ADDED Requirements

### Requirement: Explicit bounded ES retrieval strategies
The system SHALL provide es_search with BM25, VECTOR and HYBRID strategies, where topK is the per-query per-branch CHILD candidate limit. Fused-child, distinct-parent and final context budgets SHALL remain application-owned and SHALL NOT be widened by a model-selected strategy.

#### Scenario: Exact-match plan selects BM25
- **WHEN** a validated call selects BM25
- **THEN** only scoped lexical child retrieval runs and no query embedding request is made

#### Scenario: Hybrid plan has an embedding
- **WHEN** a validated HYBRID call has a valid query vector
- **THEN** independent scoped BM25 and vector TopK branches execute concurrently for that query

### Requirement: Correct per-query embedding association
The system SHALL compute or reuse the embedding corresponding to each effective normalized query when executing VECTOR or HYBRID retrieval. Successful embedding cache entries SHALL be scoped to the current run and keyed by query, model and dimensions; one subquery's vector MUST NOT be reused for another distinct subquery.

#### Scenario: Two distinct subqueries are retrieved
- **WHEN** decomposition produces queries A and B with embeddings VA and VB
- **THEN** vector retrieval for A uses VA and vector retrieval for B uses VB

#### Scenario: A later round repeats a query with a different strategy
- **WHEN** the query, embedding model and dimensions match a successful cache entry within the same run
- **THEN** that vector can be reused without sharing it across other requests

#### Scenario: Embedding fails for only one hybrid subquery
- **WHEN** A has a vector but embedding B fails
- **THEN** A retains both retrieval branches, B runs BM25 only and the result records B's vector degradation

### Requirement: Authorization and evidence validity on every attempt
The system MUST apply the same server-resolved authorization filter and CHILD type constraint before each branch TopK, fetch only authorized current parents, and revalidate scope versions on every round and evidence output boundary. No error or degradation path SHALL remove these restrictions.

#### Scenario: An inaccessible child would otherwise rank first
- **WHEN** an out-of-scope chunk has the strongest lexical or vector match
- **THEN** it occupies no branch rank and cannot appear in fused candidates or parent context

#### Scenario: A parent or revision becomes invalid between rounds
- **WHEN** accumulated evidence references a missing, superseded or unauthorized parent/revision under the existing validity contract
- **THEN** that evidence and its unusable child citations are removed rather than retained merely because an earlier round retrieved them

### Requirement: Standard fusion with multi-round provenance
The system SHALL retain standard RRF with rank constant 60 and one-based ranks, deduplicate by stable chunk key, use deterministic existing tie-breaking and preserve source query/branch/round provenance. Equivalent ranked lists SHALL NOT contribute twice merely because a callback or attempt was repeated.

#### Scenario: One child ranks in two successful branches
- **WHEN** it ranks fifth in BM25 and second in vector search
- **THEN** its accumulated score for those lists is exactly 1/65 + 1/62

#### Scenario: A new round adds a genuinely new ranked list
- **WHEN** a changed query or allowed strategy produces another successful ranking
- **THEN** the system merges its contribution with valid prior lists, deduplicates stable keys and reapplies final child and parent limits

#### Scenario: A ranked list is delivered twice
- **WHEN** the same query/branch/configuration source signature is observed again
- **THEN** the second delivery does not inflate candidate scores

### Requirement: Distinct bounded parent evidence
The system SHALL expand fused children into authorized parent bodies at most once per parent in the final context, preserve matched-child metadata and revision identity, order parents by first fused child occurrence, and enforce the context limit after all round merges.

#### Scenario: Repeated hits point to one parent
- **WHEN** several children from multiple rounds refer to the same valid parent
- **THEN** the final context contains one parent body with its merged matched-child references

#### Scenario: Combined rounds exceed the context budget
- **WHEN** merged authorized parents exceed the configured character budget
- **THEN** the assembler deterministically bounds context and drops citation entries that lack corresponding retained evidence

### Requirement: Distinguish empty results from infrastructure failure
The system SHALL distinguish successful zero-hit retrieval, partial branch degradation and total branch failure. HYBRID SHALL continue with successful branches; VECTOR-only embedding failure SHALL be an explicit failed strategy rather than silently reported vector success.

#### Scenario: Both hybrid branches fail
- **WHEN** no branch produces a successful ranked list because of infrastructure errors
- **THEN** the system returns retrieval-failed and does not call the answer model or claim that authorized knowledge is empty

#### Scenario: Both hybrid branches return zero hits
- **WHEN** ES successfully returns two empty lists
- **THEN** the graph receives a successful empty-evidence result for QA and bounded recovery decisions

#### Scenario: Vector-only embedding is unavailable
- **WHEN** the selected VECTOR strategy cannot obtain an embedding
- **THEN** the result identifies vector unavailability and any later BM25 or HYBRID attempt is explicitly replanned and budgeted
