package com.kwiki.eval;

import com.kwiki.rag.retrieval.QaRetrievalBudgets;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.wiki.domain.AppUser;
import com.kwiki.wiki.persistence.AppUserRepository;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评测服务测试装配：内存存储 / 队列 / 集群名额 / 控制总线 / run 锁共享于同一「集群」，
 * 多个 EvalRunService + EvalWorker 实例模拟多实例部署。
 */
final class EvalRunTestContext {

    final InMemoryEvalRunStore store = new InMemoryEvalRunStore();
    final InMemoryEvalWorkQueue queue = new InMemoryEvalWorkQueue();
    final InMemoryEvalClusterSlots slots = new InMemoryEvalClusterSlots();
    final InMemoryEvalControlBus bus = new InMemoryEvalControlBus();
    final InMemoryEvalRunLocks runLocks = new InMemoryEvalRunLocks();
    final EvalInstanceId instanceA = new EvalInstanceId("instance-a");
    final EvalInstanceId instanceB = new EvalInstanceId("instance-b");
    final AppUserRepository users = mock(AppUserRepository.class);

    EvalRunTestContext() {
        AppUser admin = mock(AppUser.class);
        when(admin.getId()).thenReturn(1L);
        when(admin.getUsername()).thenReturn("admin");
        when(admin.isAdmin()).thenReturn(true);
        when(admin.isActive()).thenReturn(true);
        when(users.findById(1L)).thenReturn(java.util.Optional.of(admin));
    }

    EvalRunService service(EvalDatasetLoader loader, BaselineRetrievalProbe baseline, WorkflowProbe workflow,
                           AuthorizationScopeResolver scopes, EvalCorpusService corpus, EvalReportPort report,
                           QaRetrievalBudgets budgets, EvalInstanceId instance, int concurrency,
                           int maxConcurrentRuns, List<String> refusalPatterns, Clock clock) {
        return new EvalRunService(loader, baseline, workflow, scopes, corpus, report, store, queue, slots, bus,
                runLocks, budgets, instance, concurrency, maxConcurrentRuns, refusalPatterns, clock);
    }

    /** 认领线程手动启动（不依赖 Spring 生命周期），测毕应 stop。 */
    EvalWorker worker(EvalRunService service, EvalInstanceId instance, int maxConcurrentRuns, Duration poll) {
        return new EvalWorker(service, store, queue, slots, users, instance, maxConcurrentRuns, poll,
                EvalRunService.SLOT_TTL);
    }

    static CurrentUser admin() {
        return new CurrentUser(1L, "admin", true);
    }
}
