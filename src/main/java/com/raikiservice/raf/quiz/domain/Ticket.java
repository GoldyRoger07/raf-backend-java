package com.raikiservice.raf.quiz.domain;

import java.time.Instant;

import com.raikiservice.raf.auth.domain.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 1 ticket = 1 partie. */
@Getter
@Setter
@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 24, unique = true)
    private String code;

    @Column(name = "price_paid_credits", nullable = false)
    private int pricePaidCredits;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status = TicketStatus.ACTIVE;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "refund_transaction_id")
    private Long refundTransactionId;

    /** Fournie par le client : protege du double clic sur "acheter". */
    @Column(name = "idempotency_key", length = 120, unique = true)
    private String idempotencyKey;

    @Column(name = "purchased_at", nullable = false)
    private Instant purchasedAt = Instant.now();

    @Column(name = "used_at")
    private Instant usedAt;
}
