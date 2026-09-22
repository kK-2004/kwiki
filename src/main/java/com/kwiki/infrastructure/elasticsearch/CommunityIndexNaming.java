package com.kwiki.infrastructure.elasticsearch;

/** 服务端控制的社区物理索引命名，避免管理端传入任意写目标。 */
public final class CommunityIndexNaming {

    private CommunityIndexNaming() {
    }

    public static String physicalName(long communityIndexVersion, long kbId) {
        if (communityIndexVersion < 1 || kbId < 1) {
            throw new IllegalArgumentException("社区索引版本或知识库 ID 无效");
        }
        return "kwiki-communities-v" + communityIndexVersion + "-kb" + kbId;
    }

    public static boolean isManaged(String value) {
        return value != null && value.matches("kwiki-communities-v[1-9][0-9]*-kb[1-9][0-9]*");
    }
}
