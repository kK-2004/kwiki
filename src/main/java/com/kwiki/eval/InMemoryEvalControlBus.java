package com.kwiki.eval;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 未启用 Redis 时的进程内控制总线：publish 直接分发给本进程订阅者。 */
public class InMemoryEvalControlBus implements EvalControlBus {

    private final List<ControlHandler> handlers = new CopyOnWriteArrayList<>();

    @Override
    public void publish(String runId, String action) {
        for (ControlHandler handler : handlers) {
            try {
                handler.onControl(runId, action);
            } catch (RuntimeException ignored) {
                // 单个订阅者异常不影响其他实例处理
            }
        }
    }

    @Override
    public Runnable subscribe(ControlHandler handler) {
        handlers.add(handler);
        return () -> handlers.remove(handler);
    }
}
