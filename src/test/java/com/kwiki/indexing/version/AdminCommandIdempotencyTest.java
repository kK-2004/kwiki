package com.kwiki.indexing.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminCommandIdempotencyTest {
    @Test
    void repeatedRequestReplaysStoredResponseWithoutExecutingCommand() {
        SearchIndexIdempotencyRepository rows=mock(SearchIndexIdempotencyRepository.class);
        SearchIndexIdempotency prior=SearchIndexIdempotency.pending("same","EDIT",2,"admin");
        prior.complete("SUCCESS:{\"versionNumber\":2}");
        when(rows.findById("same")).thenReturn(Optional.of(prior));
        AtomicInteger executions=new AtomicInteger();

        Map<String,Object> result=new AdminCommandIdempotency(rows,new ObjectMapper())
                .execute("same","EDIT",2,"admin",()->{executions.incrementAndGet();return Map.of();});

        assertThat(result.get("versionNumber")).isEqualTo(2);
        assertThat(executions).hasValue(0);
    }

    @Test
    void sameKeyCannotBeReusedForAnotherTarget() {
        SearchIndexIdempotencyRepository rows=mock(SearchIndexIdempotencyRepository.class);
        SearchIndexIdempotency prior=SearchIndexIdempotency.pending("same","EDIT",2,"admin");
        when(rows.findById("same")).thenReturn(Optional.of(prior));
        assertThatThrownBy(()->new AdminCommandIdempotency(rows,new ObjectMapper())
                .execute("same","EDIT",3,"admin",Map::of))
                .hasMessageContaining("another command");
    }

    @Test
    void firstRequestPersistsItsSanitizedResponse() {
        SearchIndexIdempotencyRepository rows=mock(SearchIndexIdempotencyRepository.class);
        when(rows.findById("new")).thenReturn(Optional.empty());
        when(rows.saveAndFlush(any())).thenAnswer(invocation->invocation.getArgument(0));
        when(rows.save(any())).thenAnswer(invocation->invocation.getArgument(0));

        Map<String,Object> result=new AdminCommandIdempotency(rows,new ObjectMapper())
                .execute("new","CREATE",null,"admin",()->Map.of("versionNumber",4));

        assertThat(result).containsEntry("versionNumber",4);
    }
}
