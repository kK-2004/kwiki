package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;

/** AI 辅助发布说明的应用端口。 */
public interface PublicationNotePort {
    String generate(CurrentUser user, long kbId, long pageId, String markdown);
}
