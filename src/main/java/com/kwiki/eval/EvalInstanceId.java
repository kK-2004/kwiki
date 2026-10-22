package com.kwiki.eval;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

/**
 * 实例标识：认领消费者名、集群名额 member、run 的执行实例展示都使用它。
 * 未配置 {@code kwiki.instance-id} 时按「主机名-pid-4位随机」生成，重启后变化
 * （旧标识占用的集群名额与挂起任务按到期时间自动回收）。
 */
@Component
public class EvalInstanceId {

    private final String id;

    public EvalInstanceId(@Value("${kwiki.instance-id:}") String configured) {
        this.id = configured == null || configured.isBlank() ? generate() : configured.trim();
    }

    public String get() {
        return id;
    }

    private static String generate() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        host = host.replaceAll("[^A-Za-z0-9.-]", "_");
        long pid = ProcessHandle.current().pid();
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        return host + "-" + pid + "-" + random;
    }
}
