package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/ownership-transfers")
@PreAuthorize("isAuthenticated()")
public class OwnershipTransferController {
    private final OwnershipTransferService transfers;
    public OwnershipTransferController(OwnershipTransferService transfers) { this.transfers = transfers; }
    public record CreateRequest(@NotBlank String resourceType, long resourceId, long recipientId, Long ttlSeconds) {}
    @PostMapping
    TransDTO<OwnershipTransferService.TransferCreated> create(@AuthenticationPrincipal CurrentUser user, @Valid @RequestBody CreateRequest request) { return TransDTO.success(transfers.create(user, request.resourceType(), request.resourceId(), request.recipientId(), request.ttlSeconds() == null ? null : Duration.ofSeconds(request.ttlSeconds()))); }
    @PostMapping("/{token}/accept")
    TransDTO<Void> accept(@AuthenticationPrincipal CurrentUser user, @PathVariable String token) { transfers.accept(user, token); return TransDTO.success(); }
    @DeleteMapping("/{transferId}")
    TransDTO<Void> revoke(@AuthenticationPrincipal CurrentUser user, @PathVariable long transferId) { transfers.revoke(user, transferId); return TransDTO.success(); }
}
