package com.kwiki.rag.rewrite;

import java.util.Optional;

/**
 * Query Rewrite Agent port for failure-feedback rewrites. Implementations
 * return at most one query that must differ from the original query, the last
 * query, and every historical rewrite; empty means the model produced nothing
 * usable (the call still consumes rewrite budget).
 */
public interface FeedbackRewritePort {

    Optional<String> rewrite(FeedbackRewriteInput input);
}
