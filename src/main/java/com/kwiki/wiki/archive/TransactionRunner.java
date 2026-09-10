package com.kwiki.wiki.archive;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transaction boundary helper for the recycle-bin services. In production the
 * auto-configured TransactionTemplate wraps every callback in a real database
 * transaction; in connection-free offline contexts (unit tests) the callback
 * simply runs without wrapping so the services stay bootable.
 */
@Component
public class TransactionRunner {

    private final TransactionTemplate template;

    public TransactionRunner(ObjectProvider<TransactionTemplate> template) {
        this.template = template == null ? null : template.getIfAvailable();
    }

    public void inTransaction(Runnable action) {
        if (template == null) {
            action.run();
            return;
        }
        template.executeWithoutResult(status -> action.run());
    }

    public <T> T inTransactionReturning(java.util.function.Supplier<T> action) {
        if (template == null) {
            return action.get();
        }
        return template.execute(status -> action.get());
    }
}
