package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.wiki.domain.KnowledgeBase;
import com.kwiki.wiki.persistence.KnowledgeBaseRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理端知识库清单投影：仅 id 与名称，供后台下拉选择，不暴露成员或正文。 */
@RestController
@RequestMapping("/api/v1/admin/knowledge-bases")
@PreAuthorize("hasRole('ADMIN')")
public class AdminKnowledgeBaseController {

    private final KnowledgeBaseRepository knowledgeBases;

    public AdminKnowledgeBaseController(KnowledgeBaseRepository knowledgeBases) {
        this.knowledgeBases = knowledgeBases;
    }

    public record KnowledgeBaseOption(Long id, String name) {
    }

    @GetMapping
    TransDTO<List<KnowledgeBaseOption>> list() {
        return TransDTO.success(knowledgeBases
                .findByStatusOrderByNameAsc(KnowledgeBase.STATUS_ACTIVE).stream()
                .map(kb -> new KnowledgeBaseOption(kb.getId(), kb.getName()))
                .toList());
    }
}
