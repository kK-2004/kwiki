package com.kwiki.indexing.parse;

import java.util.Locale;
import java.util.Set;

/**
 * 判定哪些附件可以进入文档索引的唯一真相来源：
 * 只有在上传时其真实内容已被校验的图片。音频、视频、
 * PDF、Office 以及所有其他附件都仅供页面展示 —— 不产生分块、
 * 不做向量嵌入、不写 ES —— 在上传、重试、工作线程与恢复
 * 重建等各条路径上均如此。Wiki 页面正文仍按发布规则建立索引，
 * 不受此分类影响。
 */
public final class AttachmentIndexEligibility {

    /** 可进入文档索引的图片内容类型。 */
    public static final Set<String> INDEXABLE_IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp");

    /** 允许上传的所有媒体内容类型（仅展示 + 预览）。 */
    public static final Set<String> MEDIA_AUDIO_TYPES = Set.of(
            "audio/mpeg", "audio/mp3", "audio/wav", "audio/ogg");
    public static final Set<String> MEDIA_VIDEO_TYPES = Set.of(
            "video/mp4", "video/webm");

    private AttachmentIndexEligibility() {
    }

    public static boolean isIndexableImage(String contentType) {
        return contentType != null
                && INDEXABLE_IMAGE_TYPES.contains(contentType.toLowerCase(Locale.ROOT));
    }

    public static boolean isAudio(String contentType) {
        return contentType != null
                && MEDIA_AUDIO_TYPES.contains(contentType.toLowerCase(Locale.ROOT));
    }

    public static boolean isVideo(String contentType) {
        return contentType != null
                && MEDIA_VIDEO_TYPES.contains(contentType.toLowerCase(Locale.ROOT));
    }

    public static boolean isMedia(String contentType) {
        return isAudio(contentType) || isVideo(contentType)
                || (contentType != null
                        && contentType.toLowerCase(Locale.ROOT).startsWith("image/"));
    }
}
