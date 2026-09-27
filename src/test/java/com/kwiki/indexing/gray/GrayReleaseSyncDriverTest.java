package com.kwiki.indexing.gray;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class GrayReleaseSyncDriverTest {

    @Test
    void 逐个推进同步中的灰度_单个失败不影响其它() {
        InMemoryGrayReleaseStore store = new InMemoryGrayReleaseStore();
        long first = store.insert("a", "kwiki-parse-2", 2, "admin");
        long second = store.insert("b", "kwiki-parse-2", 3, "admin");
        store.setStatus(first, GrayReleaseStatus.SYNCING, null);
        store.setStatus(second, GrayReleaseStatus.SYNCING, null);
        GrayReleaseService service = mock(GrayReleaseService.class);
        when(service.advance(first)).thenThrow(new IllegalStateException("boom"));

        new GrayReleaseSyncDriver(store, service).tick();

        verify(service).advance(first);
        verify(service).advance(second);
    }

    @Test
    void 没有同步中的灰度时不做任何事() {
        GrayReleaseService service = mock(GrayReleaseService.class);
        new GrayReleaseSyncDriver(new InMemoryGrayReleaseStore(), service).tick();
        verifyNoInteractions(service);
    }
}
