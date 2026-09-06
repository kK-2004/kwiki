package com.kwiki.wiki.persistence;

import com.kwiki.wiki.domain.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    Optional<Attachment> findByUuid(String uuid);

    List<Attachment> findByKbIdAndStatusOrderByIdDesc(Long kbId, String status);
}
