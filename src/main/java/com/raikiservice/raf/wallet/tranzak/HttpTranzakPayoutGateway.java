package com.raikiservice.raf.wallet.tranzak;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Client de l'API tresorerie de Tranzak ({@code /api/treasury/v1}), en-tete
 * {@code X-API-Key}. L'autorisation renvoie {@code authorization}, l'envoi
 * {@code payout} ; les lectures sont acceptees sous {@code payout}, {@code data}
 * ou a la racine, la documentation n'en donnant pas d'exemple.
 */
@Component
public class HttpTranzakPayoutGateway implements TranzakPayoutGateway {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final TranzakProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public HttpTranzakPayoutGateway(TranzakProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
        factory.setReadTimeout(properties.timeout() == null ? Duration.ofSeconds(40) : properties.timeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public boolean isConfigured() {
        return properties.payoutsConfigured();
    }

    @Override
    public Authorization authorize(NewPayout payout) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", payout.amountHtg());
        body.put("network", payout.network());
        body.put("receiver", payout.receiver());
        body.put("reference", payout.reference());
        body.put("description", payout.description());
        byte[] json = objectMapper.writeValueAsBytes(body);
        String raw = call(() -> restClient.post()
                .uri(url("/payouts/authorize"))
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", properties.payoutApiKey())
                .body(json)
                .retrieve()
                .body(String.class));
        AuthorizationJson authorization = parse(raw, AuthorizeEnvelope.class).authorization();
        if (authorization == null || authorization.token() == null || authorization.token().isBlank()) {
            throw new TranzakException("Autorisation Tranzak sans jeton : " + HttpTranzakGateway.excerpt(raw),
                    null, 200, null);
        }
        return new Authorization(authorization.token(), authorization.fee(), authorization.totalDebited());
    }

    @Override
    public Payout send(String authorizationToken) {
        byte[] json = objectMapper.writeValueAsBytes(Map.of("authorization_token", authorizationToken));
        String raw = call(() -> restClient.post()
                .uri(url("/payouts"))
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-API-Key", properties.payoutApiKey())
                .body(json)
                .retrieve()
                .body(String.class));
        return read(raw);
    }

    @Override
    public Payout get(String payoutId) {
        String raw = call(() -> restClient.get()
                .uri(url("/payouts/" + segment(payoutId)))
                .header("X-API-Key", properties.payoutApiKey())
                .retrieve()
                .body(String.class));
        return read(raw);
    }

    @Override
    public Optional<Payout> findByReference(String reference) {
        try {
            String raw = call(() -> restClient.get()
                    .uri(url("/payouts/reference/" + segment(reference)))
                    .header("X-API-Key", properties.payoutApiKey())
                    .retrieve()
                    .body(String.class));
            return Optional.of(read(raw));
        } catch (TranzakException e) {
            if (e.notFound()) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Versement sous {@code payout} (envoi), {@code data} (webhook) ou a la racine. */
    Payout read(String raw) {
        PayoutEnvelope envelope = parse(raw, PayoutEnvelope.class);
        PayoutJson json = envelope.payout() != null ? envelope.payout()
                : envelope.data() != null ? envelope.data() : parse(raw, PayoutJson.class);
        if (json.payoutId() == null && json.reference() == null) {
            throw new TranzakException("Versement Tranzak illisible : " + HttpTranzakGateway.excerpt(raw), null, 200,
                    null);
        }
        return json.toPayout();
    }

    private <T> T parse(String raw, Class<T> type) {
        try {
            return objectMapper.readValue(raw, type);
        } catch (RuntimeException e) {
            throw new TranzakException("Reponse Tranzak illisible : " + HttpTranzakGateway.excerpt(raw), null, 200, e);
        }
    }

    private static String segment(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }

    private URI url(String path) {
        String base = properties.treasuryUrl();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
    }

    private String call(Supplier<String> call) {
        if (!properties.payoutsConfigured()) {
            throw new TranzakException("Payouts Tranzak non configures (cle tk_live_ requise)", null, 0, null);
        }
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString();
            String code = null;
            String message = null;
            try {
                ErrorBody error = objectMapper.readValue(body, ErrorBody.class);
                code = error.error();
                message = error.message();
            } catch (RuntimeException ignored) {
                // Corps non JSON : le code reste inconnu.
            }
            throw new TranzakException("Tranzak a repondu " + e.getStatusCode().value() + " : "
                    + (message != null ? message : HttpTranzakGateway.excerpt(body)), code,
                    e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new TranzakException("Tranzak injoignable ou trop lent : " + e.getMessage(), null, 0, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(String error, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthorizeEnvelope(AuthorizationJson authorization) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthorizationJson(
            @JsonProperty("authorization_token") String token,
            BigDecimal fee,
            @JsonProperty("total_debited") BigDecimal totalDebited) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PayoutEnvelope(PayoutJson payout, PayoutJson data) {
    }

    /** Versement tel que Tranzak l'ecrit (envoi, lecture, webhook). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PayoutJson(
            @JsonProperty("payout_id") String payoutId,
            String reference,
            String status,
            BigDecimal amount,
            BigDecimal fee,
            @JsonProperty("failure_code") String failureCode,
            @JsonProperty("failure_message") String failureMessage,
            @JsonProperty("failure_reason") String failureReason) {

        public Payout toPayout() {
            return new Payout(payoutId, reference, status, amount, fee,
                    failureCode != null ? failureCode : failureReason, failureMessage);
        }
    }
}
