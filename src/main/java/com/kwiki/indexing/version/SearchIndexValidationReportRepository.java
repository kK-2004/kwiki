package com.kwiki.indexing.version;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface SearchIndexValidationReportRepository extends JpaRepository<SearchIndexValidationReport,Long> {
    Optional<SearchIndexValidationReport> findFirstByVersionNumberOrderByIdDesc(int versionNumber);
}
