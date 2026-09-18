package com.kwiki.indexing.version;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SearchIndexIdempotencyRepository
        extends JpaRepository<SearchIndexIdempotency, String> { }
