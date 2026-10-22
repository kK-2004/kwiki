package com.kwiki.eval;

/**
 * 跨实例控制通知（取消等）：所有实例订阅，执行实例收到后立即生效；
 * 通知丢失时由执行实例的心跳轮询兜底（读取取消标志）。
 */
public interface EvalControlBus {

    /** 向所有实例广播一条控制消息。 */
    void publish(String runId, String action);

    /** 订阅控制消息；返回注销句柄。 */
    Runnable subscribe(ControlHandler handler);

    @FunctionalInterface
    interface ControlHandler {
        void onControl(String runId, String action);
    }
}
