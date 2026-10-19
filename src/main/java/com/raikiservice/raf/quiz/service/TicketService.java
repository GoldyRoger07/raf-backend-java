package com.raikiservice.raf.quiz.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.raikiservice.raf.auth.domain.User;
import com.raikiservice.raf.auth.repository.UserRepository;
import com.raikiservice.raf.common.audit.AuditService;
import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.quiz.domain.Quiz;
import com.raikiservice.raf.quiz.domain.QuizStatus;
import com.raikiservice.raf.quiz.domain.Ticket;
import com.raikiservice.raf.quiz.domain.TicketStatus;
import com.raikiservice.raf.quiz.repository.QuizRepository;
import com.raikiservice.raf.quiz.repository.TicketRepository;
import com.raikiservice.raf.quiz.web.dto.TicketResponse;
import com.raikiservice.raf.wallet.domain.BalanceType;
import com.raikiservice.raf.wallet.domain.WalletTransaction;
import com.raikiservice.raf.wallet.domain.WalletTransactionType;
import com.raikiservice.raf.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Achat et remboursement des tickets.
 *
 * <p>C'est le point de jonction entre le quiz et le portefeuille : un ticket
 * n'existe que si le debit a reussi, et le debit n'a lieu que si tous les plafonds
 * sont respectes. Les deux se font dans la meme transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {

    private final TicketRepository ticketRepository;
    private final QuizRepository quizRepository;
    private final UserRepository userRepository;
    private final WalletService walletService;
    private final AuditService auditService;

    /**
     * Achete un ticket.
     *
     * <p>Le quiz est verrouille en premier : les plafonds (total et par joueur) et
     * le franchissement du seuil se lisent et s'ecrivent sous ce verrou, sinon deux
     * achats simultanes peuvent depasser la capacite ou declencher deux fois le
     * demarrage.
     */
    @Transactional
    public TicketResponse purchase(Long userId, Long quizId, String idempotencyKey) {
        return TicketResponse.from(purchaseEntity(userId, quizId, idempotencyKey));
    }

    @Transactional
    public Ticket purchaseEntity(Long userId, Long quizId, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<Ticket> replay = ticketRepository.findByIdempotencyKey(idempotencyKey);
            if (replay.isPresent()) {
                log.info("Achat deja traite (cle {}), rejeu ignore", idempotencyKey);
                return replay.get();
            }
        }

        Quiz quiz = quizRepository.findByIdForUpdate(quizId)
                .orElseThrow(() -> ApiException.notFound("Quiz"));

        if (!quiz.acceptsTickets()) {
            throw new ApiException(ErrorCode.QUIZ_TICKETS_CLOSED);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("Utilisateur"));

        // Verification de l'email exigee ici et pas a la connexion : on bloque
        // l'engagement d'argent, pas l'acces au compte.
        if (!user.isEmailVerified()) {
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED,
                    "Verifiez votre adresse email avant d'acheter un ticket.");
        }

        if (quiz.getMaxTicketsTotal() != null) {
            long sold = ticketRepository.countByQuizIdAndStatusNot(quizId, TicketStatus.REFUNDED);
            if (sold >= quiz.getMaxTicketsTotal()) {
                throw new ApiException(ErrorCode.QUIZ_SOLD_OUT);
            }
        }

        if (quiz.getMaxTicketsPerPlayer() != null) {
            long owned = ticketRepository.countByQuizIdAndUserIdAndStatusNot(
                    quizId, userId, TicketStatus.REFUNDED);
            if (owned >= quiz.getMaxTicketsPerPlayer()) {
                throw new ApiException(ErrorCode.QUIZ_PLAYER_LIMIT,
                        "Maximum " + quiz.getMaxTicketsPerPlayer() + " ticket(s) par joueur.");
            }
        }

        Ticket ticket = new Ticket();
        ticket.setQuiz(quiz);
        ticket.setUser(user);
        ticket.setCode(generateCode());
        ticket.setPricePaidCredits(quiz.getTicketPriceCredits());
        ticket.setStatus(TicketStatus.ACTIVE);
        ticket.setIdempotencyKey(idempotencyKey);
        ticket.setPurchasedAt(Instant.now());

        Ticket saved = ticketRepository.save(ticket);

        WalletTransaction debit = walletService.debit(
                userId,
                BalanceType.CREDIT,
                quiz.getTicketPriceCredits(),
                WalletTransactionType.TICKET_PURCHASE,
                "Ticket",
                String.valueOf(saved.getId()),
                "ticket:" + saved.getId(),
                "Ticket pour le quiz " + quiz.getTitle());

        saved.setTransactionId(debit.getId());

        maybeReachThreshold(quiz);

        auditService.record(userId, "TICKET_PURCHASED", "Ticket", String.valueOf(saved.getId()),
                null, "quiz " + quizId + ", " + quiz.getTicketPriceCredits() + " credits");

        return saved;
    }

    /**
     * Declenche la programmation du demarrage des que le seuil de joueurs
     * distincts est atteint.
     *
     * <p>Appele sous le verrou du quiz, donc execute une seule fois meme si
     * plusieurs achats arrivent ensemble.
     */
    private void maybeReachThreshold(Quiz quiz) {
        if (quiz.getStatus() != QuizStatus.REGISTRATION) {
            return;
        }

        long players = ticketRepository.countDistinctPlayers(quiz.getId());
        if (players < quiz.getMinPlayers()) {
            return;
        }

        Instant now = Instant.now();
        quiz.setThresholdReachedAt(now);
        quiz.setScheduledStartAt(now.plus(quiz.getStartDelayMinutes(), ChronoUnit.MINUTES));
        quiz.setStatus(QuizStatus.SCHEDULED);

        log.info("Quiz {} : seuil de {} joueurs atteint, demarrage programme a {}",
                quiz.getId(), quiz.getMinPlayers(), quiz.getScheduledStartAt());
    }

    /** Remboursement integral, utilise a l'annulation d'un quiz. */
    @Transactional
    public int refundAll(Long actorId, Quiz quiz, String reason) {
        List<Ticket> tickets = ticketRepository.findByQuizIdAndStatus(
                quiz.getId(), TicketStatus.ACTIVE);

        for (Ticket ticket : tickets) {
            WalletTransaction refund = walletService.credit(
                    ticket.getUser().getId(),
                    BalanceType.CREDIT,
                    ticket.getPricePaidCredits(),
                    WalletTransactionType.REFUND,
                    "Ticket",
                    String.valueOf(ticket.getId()),
                    "ticket-refund:" + ticket.getId(),
                    "Remboursement : " + reason);

            ticket.setStatus(TicketStatus.REFUNDED);
            ticket.setRefundTransactionId(refund.getId());
        }

        auditService.record(actorId, "TICKETS_REFUNDED", "Quiz", String.valueOf(quiz.getId()),
                null, tickets.size() + " ticket(s) rembourse(s) : " + reason);

        return tickets.size();
    }

    /**
     * Les vues sont assemblees dans la transaction : TicketResponse touche le
     * quiz, qui est paresseux. Sur le chemin du rejeu d'idempotence en
     * particulier, le quiz n'a jamais ete charge autrement que comme un proxy.
     */
    @Transactional(readOnly = true)
    public Page<TicketResponse> myTickets(Long userId, Pageable pageable) {
        return ticketRepository.findByUserIdOrderByPurchasedAtDesc(userId, pageable)
                .map(TicketResponse::from);
    }

    @Transactional(readOnly = true)
    public List<TicketResponse> myTicketsForQuiz(Long userId, Long quizId) {
        return ticketRepository.findByQuizIdAndUserId(quizId, userId).stream()
                .map(TicketResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean holdsTicket(Long userId, Long quizId) {
        return ticketRepository.existsByQuizIdAndUserIdAndStatusNot(
                quizId, userId, TicketStatus.REFUNDED);
    }

    private String generateCode() {
        return "RAF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()
                + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    }
}
