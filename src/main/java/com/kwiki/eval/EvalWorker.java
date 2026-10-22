package com.kwiki.eval;

import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.domain.AppUser;
import com.kwiki.wiki.persistence.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Semaphore;

/**
 * 评测认领线程：每 {@link #POLL_INTERVAL} 尝试一次认领，任一步失败则释放已拿到的名额。
 * 顺序：本实例名额（信号量 1，执行期间持有）→ 集群名额 → 读取任务（阻塞 2 秒）→
 * 状态检查（已删除 / 已取消 / 已终态的跳过；QUEUED 与心跳过期的 RUNNING 继续）→
 * 提交人校验（按 submittedBy 重新加载用户，须为启用的管理员）→ 归属（取得写入令牌）→
 * XACK 确认 → 同步执行至终态 → 释放名额。读取后未确认的消息（认领过程中宕机）由
 * 队列的挂起回收机制交给其他实例。
 */
@Component
public class EvalWorker {

    private static final Logger log = LoggerFactory.getLogger(EvalWorker.class);
    static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
    private static final Duration CLAIM_BLOCK = Duration.ofSeconds(2);

    private final EvalRunService service;
    private final EvalRunStore store;
    private final EvalWorkQueue queue;
    private final EvalClusterSlots slots;
    private final AppUserRepository users;
    private final EvalInstanceId instance;
    private final int maxConcurrentRuns;
    private final Duration pollInterval;
    private final Semaphore permit = new Semaphore(1);
    private final Duration slotTtl;
    private Thread worker;
    private volatile boolean running;

    @Autowired
    public EvalWorker(EvalRunService service, EvalRunStore store, EvalWorkQueue queue, EvalClusterSlots slots,
                      AppUserRepository users, EvalInstanceId instance,
                      @Value("${kwiki.eval.max-concurrent-runs:2}") int maxConcurrentRuns) {
        this(service, store, queue, slots, users, instance, maxConcurrentRuns, POLL_INTERVAL,
                EvalRunService.SLOT_TTL);
    }

    EvalWorker(EvalRunService service, EvalRunStore store, EvalWorkQueue queue, EvalClusterSlots slots,
               AppUserRepository users, EvalInstanceId instance, int maxConcurrentRuns, Duration pollInterval,
               Duration slotTtl) {
        this.service = service;
        this.store = store;
        this.queue = queue;
        this.slots = slots;
        this.users = users;
        this.instance = instance;
        this.maxConcurrentRuns = Math.max(1, maxConcurrentRuns);
        this.pollInterval = pollInterval;
        this.slotTtl = slotTtl;
    }

    @PostConstruct
    void start() {
        running = true;
        worker = Thread.ofPlatform().name("eval-worker-", 0).daemon(true).unstarted(this::loop);
        worker.start();
    }

    static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    /** 关闭：停止认领，先以 instance-shutdown 取消在途 run 并等待收尾（写为 CANCELLED），再中断认领线程。 */
    @PreDestroy
    void stop() {
        running = false;
        service.cancelAllForShutdown(SHUTDOWN_WAIT);
        if (worker != null) worker.interrupt();
    }

    private void loop() {
        while (running) {
            boolean executed = false;
            try {
                executed = claimOnce();
            } catch (RuntimeException e) {
                log.warn("eval worker claim failed instance={} reason={}", instance.get(), e.getMessage());
            }
            if (!executed) {
                try {
                    Thread.sleep(pollInterval.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * 一次认领尝试：名额不满足或队列空返回 false（稍后重试）；读到任务则处理并在
     * 返回前同步执行完毕。集群名额在执行期间持有，结束（含失败）时释放。
     */
    boolean claimOnce() {
        if (!permit.tryAcquire()) return false; // 本实例正在执行一个 run
        boolean slotHeld = false;
        try {
            if (!slots.tryAcquire(instance.get(), maxConcurrentRuns, slotTtl)) return false;
            slotHeld = true;
            EvalWorkQueue.ClaimedTask task = queue.claim(CLAIM_BLOCK);
            if (task == null) return false;
            process(task);
            return true;
        } finally {
            if (slotHeld) slots.release(instance.get());
            permit.release();
        }
    }

    private void process(EvalWorkQueue.ClaimedTask task) {
        String runId = task.runId();
        EvalRunStore.RunRecord record = store.find(runId).orElse(null);
        if (record == null) { // 已删除
            queue.ack(task);
            return;
        }
        if (record.cancelRequested()) {
            // 排队中取消 → CANCELLED；失联运行中取消 → 系统级中断收尾；其他实例执行中则由其收尾
            if (record.state() == EvalRunState.QUEUED) store.cancelQueued(runId);
            else if (service.isStale(record)) store.markCancelled(runId);
            queue.ack(task);
            return;
        }
        boolean stale = service.isStale(record);
        if (record.state() != EvalRunState.QUEUED && !stale) { // 终态或已被其他实例执行
            queue.ack(task);
            return;
        }
        CurrentUser submitter = resolveSubmitter(record.meta().submittedBy());
        if (submitter == null) {
            log.warn("eval run submitter not authorized runId={} submittedBy={}", runId,
                    record.meta().submittedBy());
            store.markFailed(runId, EvalRunService.SUBMITTER_NOT_AUTHORIZED);
            queue.ack(task);
            return;
        }
        if (!service.claimForExecution(runId, submitter)) return; // run 锁被占用：不确认，等挂起回收重试
        queue.ack(task);
        try {
            service.executeClaimed(runId, submitter);
        } catch (RuntimeException e) {
            log.warn("eval run execution failed run={} reason={}", runId, e.getMessage());
        }
    }

    /** 提交人按 submittedBy 重新加载：必须是启用状态的管理员（检索作用域执行时按该用户重算）。 */
    private CurrentUser resolveSubmitter(String submittedBy) {
        if (submittedBy == null || submittedBy.isBlank()) return null;
        long id;
        try {
            id = Long.parseLong(submittedBy.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return users.findById(id)
                .filter(AppUser::isActive)
                .filter(AppUser::isAdmin)
                .map(user -> new CurrentUser(user.getId(), user.getUsername(), true))
                .orElse(null);
    }
}
