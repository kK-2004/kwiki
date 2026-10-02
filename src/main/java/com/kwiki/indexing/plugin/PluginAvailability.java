package com.kwiki.indexing.plugin;

/** 插件可用性：不可用时附带面向管理员的原因。 */
public record PluginAvailability(boolean available, String unavailableReason) {
    private static final PluginAvailability OK = new PluginAvailability(true, null);

    public static PluginAvailability ok() {
        return OK;
    }

    public static PluginAvailability unavailable(String reason) {
        return new PluginAvailability(false, reason);
    }
}
