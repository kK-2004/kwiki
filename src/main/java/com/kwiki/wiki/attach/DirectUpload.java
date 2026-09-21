package com.kwiki.wiki.attach;

/*服务商定位符属于内部信息；只有 PUT URL 和过期时间可以下发给浏览器。 */
public record DirectUpload(String storageKey, String source, String putUrl, long expiresIn, Long fileId) {}
