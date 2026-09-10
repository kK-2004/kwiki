package com.kwiki.wiki.archive;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 回收站服务的事务边界辅助类。在生产环境中，自动配置的 TransactionTemplate
 * 会将每个回调包裹在真实数据库事务中；在无连接的离线环境（单元测试）下，
 * 回调直接运行、不予包裹，从而让服务保持可启动。
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
