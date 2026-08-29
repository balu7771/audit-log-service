package com.persistent.auditlog.repository;

import com.persistent.auditlog.domain.RedactionKey;
import com.persistent.auditlog.domain.RedactionKeyId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RedactionKeyRepository extends JpaRepository<RedactionKey, RedactionKeyId> {

    List<RedactionKey> findByIdSequenceId(Long sequenceId);

    Optional<RedactionKey> findByIdSequenceIdAndIdFieldPath(Long sequenceId, String fieldPath);

    List<RedactionKey> findByIdSequenceIdIn(Collection<Long> sequenceIds);

    void deleteByIdSequenceIdAndIdFieldPath(Long sequenceId, String fieldPath);
}
