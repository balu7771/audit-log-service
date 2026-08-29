package com.persistent.auditlog.repository;

import com.persistent.auditlog.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {

    Optional<AuditEvent> findBySequenceId(Long sequenceId);

    // Native bulk UPDATE (not entity save()) so only archived_at's SQL value is
    // touched; an entity-based save() risks Hibernate re-marshalling Instant
    // columns at different precision and spuriously tripping the immutability
    // trigger's byte-identical comparison on every other column.
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE audit_events SET archived_at = :archivedAt WHERE archived_at IS NULL AND server_timestamp < :cutoff",
           nativeQuery = true)
    int archiveEligible(@Param("archivedAt") Instant archivedAt, @Param("cutoff") Instant cutoff);

    long countByArchivedAtIsNotNull();
}
