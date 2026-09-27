package com.kwiki.indexing.gray;

import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GrayReleaseSyncDriverTest {

    @Test
    void 逐个推进同步中的灰度_单个失败不影响其它() throws Exception {
        InMemoryGrayReleaseStore store = new InMemoryGrayReleaseStore();
        long first = store.insert("a", "kwiki-parse-2", 2, "admin");
        long second = store.insert("b", "kwiki-parse-2", 3, "admin");
        long created = store.insert("c", "kwiki-parse-2", 4, "admin");
        long synced = store.insert("d", "kwiki-parse-2", 5, "admin");
        store.setStatus(first, GrayReleaseStatus.SYNCING, null);
        store.setStatus(second, GrayReleaseStatus.SYNCING, null);
        store.setStatus(synced, GrayReleaseStatus.SYNCED, null);
        GrayReleaseService service = mock(GrayReleaseService.class);
        when(service.advance(first)).thenThrow(new IllegalStateException("boom"));
        AutoCloseable held = mock(AutoCloseable.class);
        KwikiDistributedLocks locks = availableLocks(held);

        new GrayReleaseSyncDriver(store, service, locks).tick();

        verify(service).advance(first);
        verify(service).advance(second);
        verify(service, never()).advance(created);
        verify(service, never()).advance(synced);
        verify(held).close();
    }

    @Test
    void 未获取分布式锁时跳过本轮() {
        InMemoryGrayReleaseStore store = new InMemoryGrayReleaseStore();
        long id = store.insert("a", "kwiki-parse-2", 2, "admin");
        store.setStatus(id, GrayReleaseStatus.SYNCING, null);
        GrayReleaseService service = mock(GrayReleaseService.class);
        KwikiDistributedLocks locks = availableLocks(null);

        new GrayReleaseSyncDriver(store, service, locks).tick();

        verify(service, never()).advance(anyLong());
    }

    @Test
    void 锁服务不可用时按单实例无锁推进() {
        InMemoryGrayReleaseStore store = new InMemoryGrayReleaseStore();
        long id = store.insert("a", "kwiki-parse-2", 2, "admin");
        store.setStatus(id, GrayReleaseStatus.SYNCING, null);
        GrayReleaseService service = mock(GrayReleaseService.class);
        KwikiDistributedLocks locks = mock(KwikiDistributedLocks.class);
        when(locks.isAvailable()).thenReturn(false);

        new GrayReleaseSyncDriver(store, service, locks).tick();

        verify(service).advance(id);
        verify(locks, never()).acquire(any(), any(), any());
    }

    @Test
    void 没有同步中的灰度时不做任何事() {
        GrayReleaseService service = mock(GrayReleaseService.class);
        new GrayReleaseSyncDriver(new InMemoryGrayReleaseStore(), service,
                availableLocks(mock(AutoCloseable.class))).tick();
        verifyNoInteractions(service);
    }

    private static KwikiDistributedLocks availableLocks(AutoCloseable handle) {
        KwikiDistributedLocks locks = mock(KwikiDistributedLocks.class);
        when(locks.isAvailable()).thenReturn(true);
        when(locks.acquire(eq("gray-release-sync"), any(Duration.class), any(Duration.class)))
                .thenReturn(handle);
        return locks;
    }
}
