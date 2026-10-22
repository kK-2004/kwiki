package com.kwiki.eval;

/**
 * run 执行锁：集群内同一 run 任何时刻只能被一个实例执行的第二道防线
 * （与写入令牌互为补充）。锁服务不可用时调用方按单实例语义跳过加锁。
 */
public interface EvalRunLocks {

    /** 锁服务是否可用。 */
    boolean isAvailable();

    /** 获取 run 执行锁（长临界区，自动续期）；获取失败返回 null。返回句柄须在 finally 中关闭。 */
    AutoCloseable tryAcquire(String runId);
}
