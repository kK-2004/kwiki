package com.kwiki.indexing.plugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** 同类插件的注册表：启动时校验 id 唯一与格式，运行时按 id 解析。 */
public class PluginRegistry<T extends IndexPlugin> {
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}$");

    private final String kind;
    private final Map<String, T> byId = new TreeMap<>();

    public PluginRegistry(String kind, List<T> plugins) {
        this.kind = kind;
        for (T plugin : plugins) {
            String id = plugin.id();
            if (id == null || !ID.matcher(id).matches()) {
                throw new IllegalStateException(kind + "插件 id 格式非法：" + id
                        + "（" + plugin.getClass().getName() + "）");
            }
            T previous = byId.putIfAbsent(id, plugin);
            if (previous != null) {
                throw new IllegalStateException(kind + "插件 id 重复：" + id + "（"
                        + previous.getClass().getName() + "、" + plugin.getClass().getName() + "）");
            }
        }
    }

    public Optional<T> find(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    public T require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException(kind + "未注册：" + id));
    }

    public List<T> all() {
        return List.copyOf(byId.values());
    }

    public String kind() {
        return kind;
    }
}
