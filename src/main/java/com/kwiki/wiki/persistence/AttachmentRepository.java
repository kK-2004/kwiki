package com.kwiki.wiki.persistence;

import com.kwiki.wiki.domain.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    Optional<Attachment> findByUuid(String uuid);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from Attachment a where a.uuid = :uuid")
    Optional<Attachment> findByUuidForUpdate(@org.springframework.data.repository.query.Param("uuid") String uuid);

    List<Attachment> findByKbIdAndStatusOrderByIdDesc(Long kbId, String status);
}
