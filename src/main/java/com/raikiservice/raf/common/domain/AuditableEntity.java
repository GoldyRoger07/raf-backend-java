package com.raikiservice.raf.common.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

@Getter
@Setter
@MappedSuperclass
public abstract class AuditableEntity extends BaseEntity {

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
