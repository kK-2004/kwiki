package com.kwiki.indexing.gray;

import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 与 JdbcGrayReleaseStore 语义一致的内存实现，供服务单测使用。 */
class InMemoryGrayReleaseStore implements GrayReleaseStore {

    final Map<Long, GrayRelease> releases = new LinkedHashMap<>();
    final Map<Long, Long> activeKb = new LinkedHashMap<>();
    final Map<Integer, String> physicalNames = new LinkedHashMap<>();
    private long nextId = 1;

    @Override
    public long insert(String name, String parserVersion, int indexVersionNumber, String createdBy) {
        long id = nextId++;
        releases.put(id, new GrayRelease(id, name, parserVersion, indexVersionNumber, GrayReleaseStatus.CREATED,
                null, createdBy, Instant.EPOCH, null, null, List.of()));
        physicalNames.put(indexVersionNumber, "kwiki-chunks-v" + indexVersionNumber);
        return id;
    }

    @Override
    public void insertKbs(long releaseId, Collection<Long> kbIds) {
        List<Long> sorted = kbIds.stream().distinct().sorted().toList();
        for (Long kbId : sorted) {
            if (activeKb.containsKey(kbId)) {
                throw new DataIntegrityViolationException("duplicate active kb " + kbId);
            }
        }
        List<GrayRelease.Kb> kbs = new ArrayList<>();
        for (Long kbId : sorted) {
            activeKb.put(kbId, releaseId);
            kbs.add(new GrayRelease.Kb(kbId, "知识库 " + kbId));
        }
        GrayRelease r = releases.get(releaseId);
        releases.put(releaseId, new GrayRelease(r.id(), r.name(), r.parserVersion(), r.indexVersionNumber(),
                r.status(), r.lastError(), r.createdBy(), r.createdAt(), r.switchedAt(), r.endedAt(), kbs));
    }

    @Override
    public Optional<GrayRelease> find(long id) {
        return Optional.ofNullable(releases.get(id));
    }

    @Override
    public List<GrayRelease> findAll() {
        List<GrayRelease> all = new ArrayList<>(releases.values());
        Collections.reverse(all);
        return all;
    }

    @Override
    public Optional<GrayRelease> findByIndexVersion(int indexVersionNumber) {
        return findAll().stream().filter(r -> r.indexVersionNumber() == indexVersionNumber).findFirst();
    }

    @Override
    public List<GrayRelease> findByStatus(GrayReleaseStatus status) {
        return releases.values().stream().filter(r -> r.status() == status).toList();
    }

    @Override
    public Map<Long, Long> activeReleaseByKb(Collection<Long> kbIds) {
        Map<Long, Long> result = new LinkedHashMap<>();
        kbIds.forEach(kbId -> { if (activeKb.containsKey(kbId)) result.put(kbId, activeKb.get(kbId)); });
        return result;
    }

    @Override
    public boolean transition(long id, GrayReleaseStatus expected, GrayReleaseStatus next, String lastError) {
        if (releases.get(id).status() != expected) {
            return false;
        }
        setStatus(id, next, lastError);
        return true;
    }

    /** 仅供测试准备数据：无条件设置状态。 */
    void setStatus(long id, GrayReleaseStatus status, String lastError) {
        GrayRelease r = releases.get(id);
        releases.put(id, new GrayRelease(r.id(), r.name(), r.parserVersion(), r.indexVersionNumber(), status,
                lastError, r.createdBy(), r.createdAt(), r.switchedAt(), r.endedAt(), r.kbs()));
    }

    @Override
    public void rename(long id, String name) {
        GrayRelease r = releases.get(id);
        releases.put(id, new GrayRelease(r.id(), name, r.parserVersion(), r.indexVersionNumber(), r.status(),
                r.lastError(), r.createdBy(), r.createdAt(), r.switchedAt(), r.endedAt(), r.kbs()));
    }

    @Override
    public boolean markSwitched(long id) {
        GrayRelease r = releases.get(id);
        if (r.status() != GrayReleaseStatus.SYNCED) {
            return false;
        }
        releases.put(id, new GrayRelease(r.id(), r.name(), r.parserVersion(), r.indexVersionNumber(),
                GrayReleaseStatus.SWITCHED, null, r.createdBy(), r.createdAt(), Instant.EPOCH, r.endedAt(), r.kbs()));
        return true;
    }

    @Override
    public boolean end(long id, GrayReleaseStatus expected) {
        GrayRelease r = releases.get(id);
        if (r.status() != expected) {
            return false;
        }
        releases.put(id, new GrayRelease(r.id(), r.name(), r.parserVersion(), r.indexVersionNumber(),
                GrayReleaseStatus.ENDED, r.lastError(), r.createdBy(), r.createdAt(), r.switchedAt(), Instant.EPOCH,
                r.kbs()));
        activeKb.values().removeIf(releaseId -> releaseId == id);
        return true;
    }

    @Override
    public List<SwitchedRoute> switchedRoutes() {
        List<SwitchedRoute> routes = new ArrayList<>();
        for (GrayRelease r : findByStatus(GrayReleaseStatus.SWITCHED)) {
            for (Long kbId : r.kbIds()) {
                routes.add(new SwitchedRoute(physicalNames.get(r.indexVersionNumber()), r.parserVersion(), kbId));
            }
        }
        return routes;
    }
}
