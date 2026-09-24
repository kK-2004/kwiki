package com.kwiki.testutil;

import com.kwiki.wiki.persistence.AppUserRepository;
import com.kwiki.wiki.persistence.ArchiveBatchItemRepository;
import com.kwiki.wiki.persistence.ArchiveBatchRepository;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.KnowledgeBaseMemberRepository;
import com.kwiki.wiki.persistence.KnowledgeBaseRepository;
import com.kwiki.wiki.persistence.SourceDocumentRepository;
import com.kwiki.wiki.persistence.WikiLinkRepository;
import com.kwiki.wiki.persistence.WikiPageDraftRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;
import com.kwiki.wiki.persistence.WikiPageRevisionRepository;
import com.kwiki.wiki.persistence.WikiPageTagRepository;
import com.kwiki.wiki.persistence.WikiTagRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * 供无 MySQL 的启动上下文测试使用的模拟持久化层。
 * Wiki 服务强依赖仓储 bean；该配置让这些
 * 上下文无需连接，而由环境变量开关控制的契约测试仍会校验真实 SQL。
 */
@TestConfiguration
public class WikiMockBeans {

    /** 上下文测试不访问外部 provider；能力探测由专用契约测试覆盖。 */
    @MockBean
    public com.kwiki.infrastructure.ai.AnswerModelCapabilities answerModelCapabilities;

    /**
     * 搜索索引与图构建的持久化支撑层在此一并模拟：这些 bean 现已随应用
     * 无条件装配（此前的 @ConditionalOnBean 评估时机错误导致从未生效），
     * 无数据库的上下文需要以下替身才能完整启动。
     */
    @MockBean
    public org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @MockBean
    public org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** 真实注册表而非 mock：MeterRegistry 的接口默认方法被 actuator 依赖，mock 会返回 null。 */
    @org.springframework.context.annotation.Bean
    public io.micrometer.core.instrument.MeterRegistry meterRegistry() {
        return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    }

    @MockBean
    public com.kwiki.indexing.search.ElasticsearchIndexManager elasticsearchIndexManager;

    @MockBean
    public com.kwiki.infrastructure.redis.KwikiDistributedLocks distributedLocks;

    @MockBean
    public com.kwiki.indexing.version.SearchIndexVersionRepository searchIndexVersions;

    @MockBean
    public com.kwiki.indexing.version.SearchIndexRebuildRunRepository searchIndexRebuildRuns;

    @MockBean
    public com.kwiki.indexing.version.SearchIndexRebuildRangeRepository searchIndexRebuildRanges;

    @MockBean
    public com.kwiki.indexing.version.SearchIndexValidationReportRepository searchIndexValidationReports;

    @MockBean
    public com.kwiki.indexing.version.SearchIndexAuditRepository searchIndexAudits;

    @MockBean
    public com.kwiki.indexing.version.SearchIndexIdempotencyRepository searchIndexIdempotency;

    @MockBean
    public KnowledgeBaseRepository knowledgeBases;

    @MockBean
    public KnowledgeBaseMemberRepository members;

    @MockBean
    public WikiPageRepository pages;

    @MockBean
    public WikiPageRevisionRepository revisions;

    @MockBean
    public WikiPageDraftRepository drafts;

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
