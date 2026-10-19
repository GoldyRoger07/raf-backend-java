package com.raikiservice.raf.quiz.domain;

import com.raikiservice.raf.media.domain.MediaFile;
import com.raikiservice.raf.question.domain.Category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Sujet candidat au vote. */
@Getter
@Setter
@Entity
@Table(name = "quiz_topics")
public class QuizTopic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, length = 160)
    private String label;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "media_id")
    private MediaFile media;

    @Column(name = "vote_count", nullable = false)
    private int voteCount;
}
