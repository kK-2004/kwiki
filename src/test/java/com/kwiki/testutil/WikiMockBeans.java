package com.kwiki.testutil;

import com.kwiki.wiki.persistence.AppUserRepository;
import com.kwiki.wiki.persistence.ArchiveBatchItemRepository;
import com.kwiki.wiki.persistence.ArchiveBatchRepository;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository;
import com.kwiki.wiki.persistence.KnowledgeBaseRepository;
import com.kwiki.wiki.persistence.SourceDocumentRepository;
import com.kwiki.wiki.persistence.WikiLinkRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;
import com.kwiki.wiki.persistence.WikiPageRevisionRepository;
import com.kwiki.wiki.persistence.WikiPageTagRepository;
import com.kwiki.wiki.persistence.WikiTagRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Mocked persistence layer for context-booting tests that run without MySQL.
 * Wiki services hard-depend on repository beans; this configuration keeps those
 * contexts connection-free while the env-gated contract tests validate real SQL.
 */
@TestConfiguration
public class WikiMockBeans {

    @MockBean
    public KnowledgeBaseRepository knowledgeBases;

    @MockBean
    public KnowledgeBaseMemberRepository members;

    @MockBean
    public WikiPageRepository pages;

    @MockBean
    public WikiPageRevisionRepository revisions;

    @MockBean
    public WikiLinkRepository links;

    @MockBean
    public WikiTagRepository tags;

    @MockBean
    public WikiPageTagRepository pageTags;

    @MockBean
    public AttachmentRepository attachments;

    @MockBean
    public SourceDocumentRepository sources;

    @MockBean
    public AppUserRepository users;

    @MockBean
    public ArchiveBatchRepository archiveBatches;

    @MockBean
    public ArchiveBatchItemRepository archiveBatchItems;
}
