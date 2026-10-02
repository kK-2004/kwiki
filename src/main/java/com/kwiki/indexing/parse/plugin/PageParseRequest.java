package com.kwiki.indexing.parse.plugin;

import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.domain.WikiPageRevision;

/** 页面解析输入，携带页面及待解析的修订版本。 */
public record PageParseRequest(WikiPage page, WikiPageRevision revision) {
}
