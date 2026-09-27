package com.kwiki.indexing.gray;

/** 灰度状态：切回不是独立状态——SWITCHED 切回后回到 SYNCED（仍双写，可再次切换）。 */
public enum GrayReleaseStatus {
    CREATED, SYNCING, SYNCED, SWITCHED, ENDED
}
