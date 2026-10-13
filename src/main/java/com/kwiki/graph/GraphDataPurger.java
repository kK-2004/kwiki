package com.kwiki.graph;

/**
 * 清空图存储中的全部图数据（保留 schema）。仅供索引强制重置使用：重置后 graphVersion
 * 会从 1 重新分配，残留数据必须先清除，否则新构建会与旧版本数据混在一起。
 */
public interface GraphDataPurger {

    /** 返回被清空的类型名。 */
    java.util.List<String> purgeAll();
}
