package com.kwiki.wiki.api;

import com.kwiki.security.CurrentUser;

/** Application port for AI-assisted publication notes. */
public interface PublicationNotePort {
    String generate(CurrentUser user, long kbId, long pageId, String markdown);
}
