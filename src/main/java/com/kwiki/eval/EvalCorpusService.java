package com.kwiki.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.security.CurrentUser;
import com.kwiki.wiki.api.PageIndexStatusService;
import com.kwiki.wiki.api.PageRevisionService;
import com.kwiki.wiki.api.WikiTreeService;
import com.kwiki.wiki.domain.WikiPage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 评测语料导入与就绪检查。导入与用户手动建页走同一条链路：
 * createNode → saveDraft → publish（publish 同事务入队索引任务）。按标题幂等。
 */
@Service
public class EvalCorpusService {

    static final String CORPUS_ROOT = "eval/corpus/";
    private static final Pattern CORPUS_NAME = Pattern.compile("[a-z0-9-]+");

    public record ManifestEntry(String file, String title, String domain, int docId) {
    }

    public record ImportResult(List<Long> createdPageIds, List<String> skippedTitles) {
    }

    private final WikiTreeService tree;
    private final PageRevisionService revisions;
    private final ObjectProvider<PageIndexStatusService> indexStatuses;
    private final ObjectMapper mapper;

    public EvalCorpusService(WikiTreeService tree, PageRevisionService revisions,
                             ObjectProvider<PageIndexStatusService> indexStatuses, ObjectMapper mapper) {
        this.tree = tree;
        this.revisions = revisions;
        this.indexStatuses = indexStatuses;
        this.mapper = mapper;
    }

    public ImportResult importCorpus(CurrentUser user, long kbId, String corpus) {
        List<ManifestEntry> manifest = manifest(corpus);
        Set<String> existing = new HashSet<>();
        pages(tree.tree(user, kbId)).forEach(node -> existing.add(node.title()));
        List<Long> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (ManifestEntry entry : manifest) {
            if (existing.contains(entry.title())) {
                skipped.add(entry.title());
                continue;
            }
            String markdown = document(corpus, entry.file());
            WikiPage page = tree.createNode(user, kbId, null, entry.title(), WikiPage.TYPE_PAGE, null);
            revisions.saveDraft(user, kbId, page.getId(), markdown, "评测语料导入 " + corpus, null);
            revisions.publish(user, kbId, page.getId());
            created.add(page.getId());
        }
        return new ImportResult(List.copyOf(created), List.copyOf(skipped));
    }

    /** 评测 KB 至少有一个页面，且全部页面索引就绪。 */
    public void requireReady(CurrentUser user, long kbId) {
        List<WikiTreeService.TreeNodeView> pages = pages(tree.tree(user, kbId));
        if (pages.isEmpty()) throw new EvalException(409, "corpus-empty");
        PageIndexStatusService statuses = indexStatuses.getIfAvailable();
        if (statuses == null) return;
        long notReady = pages.stream()
                .filter(page -> statuses.status(user, kbId, page.id()).status()
                        != PageIndexStatusService.IndexStatus.READY)
                .count();
        if (notReady > 0) {
            throw new EvalException(409, "corpus-not-ready: " + notReady + " pages not indexed");
        }
    }

    public List<ManifestEntry> manifest(String corpus) {
        requireCorpusName(corpus);
        try (InputStream in = new ClassPathResource(CORPUS_ROOT + corpus + "/manifest.json").getInputStream()) {
            return mapper.readValue(in, new TypeReference<List<ManifestEntry>>() { });
        } catch (IOException e) {
            throw new EvalException(404, "corpus-not-found: " + corpus);
        }
    }

    public String document(String corpus, String file) {
        requireCorpusName(corpus);
        if (!file.matches("[A-Za-z0-9._-]+\\.md")) throw new EvalException(400, "invalid-corpus-file");
        try (InputStream in = new ClassPathResource(CORPUS_ROOT + corpus + "/" + file).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new EvalException(404, "corpus-file-not-found: " + file);
        }
    }

    private static void requireCorpusName(String corpus) {
        if (corpus == null || !CORPUS_NAME.matcher(corpus).matches()) {
            throw new EvalException(400, "invalid-corpus-name");
        }
    }

    private static List<WikiTreeService.TreeNodeView> pages(List<WikiTreeService.TreeNodeView> roots) {
        List<WikiTreeService.TreeNodeView> pages = new ArrayList<>();
        collect(roots, pages);
        return pages;
    }

    private static void collect(List<WikiTreeService.TreeNodeView> nodes, List<WikiTreeService.TreeNodeView> out) {
        if (nodes == null) return;
        for (WikiTreeService.TreeNodeView node : nodes) {
            if (WikiPage.TYPE_PAGE.equals(node.nodeType())) out.add(node);
            collect(node.children(), out);
        }
    }
}
