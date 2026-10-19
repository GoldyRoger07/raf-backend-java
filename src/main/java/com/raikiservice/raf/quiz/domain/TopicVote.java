package com.raikiservice.raf.quiz.domain;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.common.domain.BaseEntity;

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
@Table(name = "topic_votes")
public class TopicVote extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_topic_id", nullable = false)
    private QuizTopic topic;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
}
