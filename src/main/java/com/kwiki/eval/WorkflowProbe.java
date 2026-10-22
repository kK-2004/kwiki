package com.kwiki.eval;

import com.kwiki.rag.answer.ChatStreamEvent;
import com.kwiki.rag.orchestration.AgenticWorkflowPort;
import com.kwiki.rag.orchestration.RouteInput;
import com.kwiki.rag.orchestration.RunObserver;
import com.kwiki.security.CurrentUser;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 以不落会话的方式跑一次完整 QA 工作流（限定评测知识库），从 SSE 事件取回答与终态，
 * 从运行观测取最后一次送入生成的上下文（全文与按证据拆分的片段）与改写历史。
 */
@Component
public class WorkflowProbe {

    public record Result(String outcome, String errorCode, String answer, String context,
                         List<RunObserver.ContextSegment> contextSegments, List<String> rewrites) {
        public Result {
            contextSegments = contextSegments == null ? List.of() : List.copyOf(contextSegments);
        }
    }

    private final AgenticWorkflowPort workflow;

    public WorkflowProbe(AgenticWorkflowPort workflow) {
        this.workflow = workflow;
    }

    public Result run(CurrentUser user, long kbId, String query, Duration timeout) {
        List<RunObserver.Event> observed = new CopyOnWriteArrayList<>();
        RunObserver evalObserver = new RunObserver() {
            @Override
            public void onEvent(Event event) {
                observed.add(event);
            }

            @Override
            public com.kwiki.usage.UsageSource usageSource() {
                return com.kwiki.usage.UsageSource.EVAL;
            }
        };
        List<ChatStreamEvent> events = workflow.answerInSession(user, query, List.of(), Set.of(kbId), Set.of(),
                RouteInput.NON_INTERACTIVE, evalObserver).collectList().block(timeout);
        StringBuilder answer = new StringBuilder();
        String outcome = null;
        String error = null;
        for (ChatStreamEvent event : events == null ? List.<ChatStreamEvent>of() : events) {
            switch (event.type()) {
                case "token" -> answer.append(String.valueOf(event.payloadMap().get("text")));
                case "done" -> outcome = String.valueOf(event.payloadMap().get("outcome"));
                case "error" -> error = String.valueOf(event.payloadMap().get("error"));
                default -> { }
            }
        }
        String context = "";
        List<RunObserver.ContextSegment> segments = List.of();
        List<String> rewrites = new ArrayList<>();
        for (RunObserver.Event event : observed) {
            if (event instanceof RunObserver.ContextObserved contextEvent) {
                context = contextEvent.context();
                segments = contextEvent.segments();
            } else if (event instanceof RunObserver.RewriteObserved rewrite) rewrites.add(rewrite.newQuery());
        }
        return new Result(outcome, error, answer.toString(), context, segments, List.copyOf(rewrites));
    }
}
