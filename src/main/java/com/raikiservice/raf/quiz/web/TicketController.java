package com.raikiservice.raf.quiz.web;

import com.raikiservice.raf.auth.security.UserPrincipal;
import com.raikiservice.raf.quiz.service.TicketService;
import com.raikiservice.raf.quiz.web.dto.TicketResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
@Tag(name = "Quiz")
public class TicketController {

    private final TicketService ticketService;

    @GetMapping
    @Operation(summary = "Mes tickets, tous quiz confondus, du plus recent au plus ancien")
    public Page<TicketResponse> mine(@AuthenticationPrincipal UserPrincipal player,
            @PageableDefault(size = 50) Pageable pageable) {
        return ticketService.myTickets(player.getId(), pageable);
    }
}
