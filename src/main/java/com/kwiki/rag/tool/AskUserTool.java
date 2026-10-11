package com.kwiki.rag.tool;

import com.kwiki.rag.retrieval.ChildEvidence;
import com.kwiki.rag.routing.ClarifyRequest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ask_user：planner 在证据显示多种解读（不同知识库 / 页面都命中）或缺关键限定条件时反问。
 * 选项只能引用证据池里真实存在的子块，kbId / pageId 由服务端从证据派生，LLM 无法凭空造选项或范围。
 * 本工具只登记反问；是否结束 run 由工作流在 execute 之后读取 {@link EvidencePool#clarification()} 决定。
 */
@Component
public class AskUserTool implements AgentToolExecutor {

    @Override
    public String name() {
        return AgentTools.ASK_USER;
    }

    @Override
    public Outcome execute(AgentToolArguments arguments, ToolExecutionContext context) {
        if (!(arguments instanceof AgentToolArguments.AskUser args)) {
            throw new ToolFailure("invalid-arguments");
        }
        EvidencePool pool = context.pool();
        if (pool.clarification() != null) {
            throw new ToolFailure("already-asked");
        }
        List<ClarifyRequest.Option> options = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        for (AgentToolArguments.AskUser.Option option : args.options()) {
            ChildEvidence child = pool.child(option.ref()).orElseThrow(() -> new ToolFailure("unknown-child-key"));
            Long pageId = "PAGE".equals(child.resourceType()) ? child.resourceId() : null;
            if (!targets.add(child.kbId() + ":" + pageId)) {
                throw new ToolFailure("duplicate-option-target");
            }
            if (option.label() == null || option.label().isBlank()) {
                throw new ToolFailure("invalid-clarification");
            }
            options.add(new ClarifyRequest.Option("o" + (options.size() + 1), option.label().strip(),
                    child.kbId(), pageId));
        }
        ClarifyRequest request;
        try {
            request = new ClarifyRequest(args.question(), options, args.allowFreeText(), "planner");
        } catch (IllegalArgumentException invalid) {
            throw new ToolFailure("invalid-clarification");
        }
        pool.requestClarification(request);
        return new Outcome(List.of(), List.of(), Map.of("optionCount", options.size()));
    }
}
