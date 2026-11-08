package com.raikiservice.raf.wallet.service;

import java.nio.charset.StandardCharsets;

import com.raikiservice.raf.common.error.ApiException;
import com.raikiservice.raf.common.error.ErrorCode;
import com.raikiservice.raf.wallet.tranzak.TranzakProperties;
import com.raikiservice.raf.wallet.tranzak.TranzakSignature;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Point d'entree des webhooks Tranzak : verifie la signature (HMAC-SHA256 du
 * corps brut, secret du website), puis aiguille selon l'evenement : payment.*
 * vers les recharges en ligne, payout.* vers les retraits automatiques.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranzakWebhookService {

    private final TranzakProperties properties;
    private final OnlineTopUpService onlineTopUpService;
    private final AutoPayoutService autoPayoutService;
    private final ObjectMapper objectMapper;

    public void handle(byte[] body, String signature) {
        if (!TranzakSignature.verify(properties.webhookSecret(), body, signature)) {
            log.warn("Webhook Tranzak refuse : signature absente ou invalide");
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Signature invalide.");
        }
        String raw = new String(body, StandardCharsets.UTF_8);
        String event;
        try {
            event = objectMapper.readValue(raw, EventName.class).event();
        } catch (RuntimeException e) {
            log.error("Webhook Tranzak illisible : {}", e.getMessage());
            return;
        }
        if (event != null && event.startsWith("payout.")) {
            autoPayoutService.handleWebhook(raw);
        } else {
            onlineTopUpService.handlePaymentEvent(raw);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EventName(String event) {
    }
}
