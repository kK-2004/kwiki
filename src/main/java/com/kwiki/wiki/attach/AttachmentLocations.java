package com.kwiki.wiki.attach;

import java.util.Optional;

/**
 * 附件在内容中心的真实存储位置，以及内容丢失的登记。存储适配器据此优先按
 * 「对象键 + 数据源」读取文件；内容中心确认文件不存在时登记丢失，
 * 使上传去重不再复用该文件，并让上层给出「文件已丢失」的提示。
 */
public interface AttachmentLocations {

    record Location(String storageKey, String source) {
    }

    /** 该内容中心文件记录的对象键与数据源；上传时未记录（历史数据）则为空。 */
    Optional<Location> locate(long contentCenterFileId);

    /** 内容中心确认该文件不存在。 */
    void markMissing(long contentCenterFileId);
}
