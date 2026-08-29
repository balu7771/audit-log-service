package com.persistent.auditlog.domain;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RedactionKeyId implements Serializable {

    private Long sequenceId;
    private String fieldPath;
}
