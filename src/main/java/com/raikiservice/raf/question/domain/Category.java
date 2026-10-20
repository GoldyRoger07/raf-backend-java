package com.raikiservice.raf.question.domain;

import com.raikiservice.raf.common.domain.AuditableEntity;
import com.raikiservice.raf.media.domain.MediaFile;
import com.raikiservice.raf.quiz.domain.GameMode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "categories")
public class Category extends AuditableEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 140, unique = true)
    private String slug;

    @Column(length = 500)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "media_id")
    private MediaFile media;

    @Column(nullable = false)
    private boolean active = true;

    /** Le sujet peut servir a un quiz de groupe. */
    @Column(name = "group_enabled", nullable = false)
    private boolean groupEnabled = true;

    /** Le sujet peut servir a un quiz solo. */
    @Column(name = "solo_enabled", nullable = false)
    private boolean soloEnabled = true;

    public boolean isAvailableFor(GameMode mode) {
        return active && (mode == GameMode.SOLO ? soloEnabled : groupEnabled);
    }
}
