package com.kwiki.rag.answer;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/chat/sessions")
@PreAuthorize("isAuthenticated()")
public class ChatSessionController {
    private final ChatSessionService sessions;
    private final ChatRunEventStore runEvents;

    public ChatSessionController(ChatSessionService sessions, ChatRunEventStore runEvents) {
        this.sessions = sessions;
        this.runEvents = runEvents;
    }
    public record RenameRequest(@NotBlank String title) {}

    /**
     * Stored wire events of one run in seq order, for replaying the activity
     * timeline when a conversation is reopened. Runs from before this table
     * existed simply return an empty list.
     */
    @GetMapping("/{sessionId}/runs/{requestId}/events")
    TransDTO<List<ChatRunEventStore.StoredEvent>> runEvents(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable String sessionId,
            @PathVariable String requestId) {
        sessions.detail(user, sessionId); // ownership check
        return TransDTO.success(runEvents.replay(requestId));
    }

    @GetMapping
    TransDTO<List<ChatSessionService.SessionView>> list(@AuthenticationPrincipal CurrentUser user) {
        return TransDTO.success(sessions.list(user));
    }

    @GetMapping("/page")
    TransDTO<ChatSessionService.SessionPage> page(@AuthenticationPrincipal CurrentUser user,
                                                   @RequestParam(defaultValue = "50") int limit,
                                                   @RequestParam(required = false) String cursor) {
        return TransDTO.success(sessions.listPage(user, limit, cursor));
    }

    @GetMapping("/{sessionId}")
    TransDTO<ChatSessionService.SessionDetail> detail(@AuthenticationPrincipal CurrentUser user, @PathVariable String sessionId) {
        return TransDTO.success(sessions.detail(user, sessionId));
    }

    @PatchMapping("/{sessionId}")
    TransDTO<Void> rename(@AuthenticationPrincipal CurrentUser user, @PathVariable String sessionId,
                          @Valid @RequestBody RenameRequest request) {
        sessions.rename(user, sessionId, request.title()); return TransDTO.success();
    }

    @DeleteMapping("/{sessionId}")
    TransDTO<Void> delete(@AuthenticationPrincipal CurrentUser user, @PathVariable String sessionId) {
        sessions.delete(user, sessionId); return TransDTO.success();
    }

    public record CancelRequest(String partialAnswer) {}

    @org.springframework.web.bind.annotation.PostMapping("/{sessionId}/runs/{requestId}/cancel")
    TransDTO<Void> cancel(@AuthenticationPrincipal CurrentUser user,
                          @PathVariable String sessionId,
                          @PathVariable String requestId,
                          @RequestBody(required = false) CancelRequest request) {
        sessions.cancel(user, sessionId, requestId, request == null ? null : request.partialAnswer()); return TransDTO.success();
    }
}
