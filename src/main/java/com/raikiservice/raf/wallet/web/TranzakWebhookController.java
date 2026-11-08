package com.raikiservice.raf.wallet.web;

import com.raikiservice.raf.wallet.service.OnlineTopUpService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Notifications de la passerelle Tranzak, sans connexion : seule la signature
 * HMAC du corps brut (secret du website) prouve qu'elles viennent de Tranzak.
 * Le corps est lu en octets, tel que recu, pour que la signature se verifie.
 */
@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Tag(name = "Webhooks")
public class TranzakWebhookController {

    private final OnlineTopUpService onlineTopUpService;

    @PostMapping("/tranzak")
    @Operation(summary = "Webhook Tranzak (paiement reussi ou echoue)")
    public ResponseEntity<Void> tranzak(@RequestBody(required = false) byte[] body,
            @RequestHeader(value = "X-Tranzak-Signature", required = false) String signature) {
        onlineTopUpService.handleWebhook(body == null ? new byte[0] : body, signature);
        return ResponseEntity.ok().build();
    }
}
