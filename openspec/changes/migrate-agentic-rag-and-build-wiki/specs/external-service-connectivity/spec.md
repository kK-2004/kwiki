## ADDED Requirements

### Requirement: External middleware configuration
The system SHALL connect to operator-provided MySQL, Redis, MinIO, and Elasticsearch services using explicit runtime configuration and SHALL NOT create or require middleware containers.

#### Scenario: Application starts with valid external endpoints
- **WHEN** an operator supplies valid endpoints and credentials for all required middleware
- **THEN** the application starts without invoking Docker or provisioning any middleware

#### Scenario: Repository is prepared for local configuration
- **WHEN** a developer inspects the repository setup files
- **THEN** the repository contains a secret-free environment example and contains no required Docker Compose middleware stack

### Requirement: Independent Qwen embedding credential
The system MUST use Qwen `text-embedding-v4` through a `kwiki`-specific API credential and MUST NOT embed a credential or reuse a credential copied from `k-Rag`.

#### Scenario: Embedding credential is missing
- **WHEN** the embedding capability is enabled but `KWIKI_QWEN_EMBEDDING_API_KEY` is absent or blank
- **THEN** configuration validation fails before the application accepts traffic

#### Scenario: Embedding request is issued
- **WHEN** the system vectorizes Wiki content or a query
- **THEN** it calls `text-embedding-v4` using the independently supplied `kwiki` credential

### Requirement: Dependency readiness and safe diagnostics
The system SHALL expose readiness for MySQL, Redis, MinIO, Elasticsearch, and enabled AI providers without exposing secrets in logs or responses.

#### Scenario: A required dependency is unavailable
- **WHEN** a readiness probe cannot reach a required external service
- **THEN** readiness reports that dependency as unavailable while liveness keeps the process running

#### Scenario: A credentialed call fails
- **WHEN** an external client records an authentication or connection error
- **THEN** logs identify the provider and correlation ID but omit credential values and sensitive headers

### Requirement: Embedding dimension consistency
The system SHALL use one validated embedding-dimension configuration for both Qwen responses and the Elasticsearch dense-vector mapping.

#### Scenario: Returned vector has the configured dimension
- **WHEN** Qwen returns an embedding matching the configured dimension
- **THEN** the system accepts the vector for indexing or retrieval

#### Scenario: Returned vector has a different dimension
- **WHEN** Qwen returns an embedding whose length differs from the configured Elasticsearch mapping dimension
- **THEN** the system rejects the vector, records a diagnosable failure, and does not write a malformed document
