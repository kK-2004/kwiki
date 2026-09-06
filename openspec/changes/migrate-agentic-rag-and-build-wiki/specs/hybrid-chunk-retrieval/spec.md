## ADDED Requirements

### Requirement: Independent child-chunk BM25 and vector recall
The system SHALL run independent Elasticsearch BM25 and dense-vector TopK searches over `CHILD` documents for each effective query and SHALL execute the two branches concurrently when an embedding is available.

#### Scenario: Both branches succeed
- **WHEN** text and query embedding are available and Elasticsearch accepts both searches
- **THEN** the system obtains two independently ranked child candidate lists without returning parent documents or combining raw branch scores

#### Scenario: Embedding cannot be produced
- **WHEN** query embedding fails or is unavailable
- **THEN** the BM25 branch still runs and the retrieval trace identifies vector degradation

### Requirement: Authorization before branch ranking
The system MUST apply the resolved knowledge-base scope to every branch before Elasticsearch selects that branch's TopK.

#### Scenario: Candidate is outside scope
- **WHEN** an out-of-scope chunk matches text or vector similarity
- **THEN** it does not occupy a branch rank or appear in fused candidates

### Requirement: Standard reciprocal rank fusion
The system SHALL fuse every successful ranked list using `score(chunk) = Σ 1 / (rankConstant + rank_i)` with ranks starting at one and SHALL NOT use Elasticsearch raw scores as fusion weights.

#### Scenario: Chunk appears in both branches
- **WHEN** a chunk ranks fifth in BM25 and second in vector search with rank constant 60
- **THEN** its fusion score is exactly `1/65 + 1/62`

#### Scenario: Decomposed query produces multiple lists
- **WHEN** two subqueries each produce BM25 and vector rankings
- **THEN** RRF accumulates contributions from all successful lists by stable chunk key

### Requirement: Stable deduplication and ordering
The system SHALL merge duplicate candidates by stable chunk key and SHALL use deterministic tie-breaking based on branch ranks and the stable key.

#### Scenario: Same chunk occurs in multiple lists
- **WHEN** rankings contain the same resource, revision, and ordinal chunk
- **THEN** the fused output contains one candidate with all source ranks and accumulated contributions

#### Scenario: Two candidates have equal fusion score
- **WHEN** candidates have equal RRF scores
- **THEN** ordering is determined by best rank, BM25 rank, vector rank, and stable chunk key in that order

### Requirement: Distinct parent context expansion
The system SHALL traverse fused child candidates in rank order, group them by `parentChunkKey`, and batch-fetch each authorized parent at most once for generation context.

#### Scenario: Multiple winning children share one parent
- **WHEN** two or more fused child candidates reference the same parent
- **THEN** the generation evidence contains one parent body with the best child rank and all matched child locations attached

#### Scenario: Winning children reference different parents
- **WHEN** fused children reference multiple authorized parents
- **THEN** parent evidence order follows the first ranked child occurrence for each distinct parent

#### Scenario: Parent is missing or no longer authorized
- **WHEN** a parent lookup cannot resolve a current authorized parent for a child candidate
- **THEN** that parent and its children are omitted without substituting content from outside scope

### Requirement: Branch degradation without scope expansion
The system SHALL continue with the successful branch when one branch fails and SHALL return a structured retrieval error when no branch yields a usable result.

#### Scenario: BM25 fails and vector succeeds
- **WHEN** the BM25 request fails but the scoped vector request succeeds
- **THEN** vector candidates are fused as a single list and the response records BM25 degradation

#### Scenario: Both branches fail
- **WHEN** neither branch returns a usable ranked list due to errors
- **THEN** no generation call is made and the client receives a diagnosable retrieval failure

### Requirement: Bounded retrieval budgets
The system SHALL validate configurable child-branch, fused-child, distinct-parent, subquery, timeout, and total parent-context limits before executing retrieval.

#### Scenario: Requested final count is out of range
- **WHEN** a caller requests a final parent count or parent-context budget outside configured bounds
- **THEN** the system rejects the request instead of silently widening the budget
