package com.raikiservice.raf.wallet.tranzak;

import java.math.BigDecimal;
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
 * Client de l'API passerelle de Tranzak Haiti ({@code docs.tranzak.co}) :
 * authentification par l'en-tete {@code X-Api-Key}, la cle choisit le mode
 * (tk_test_ ou tk_live_).
 *
 * <p>La creation renvoie le paiement a la racine du JSON, les lectures sous
 * {@code data} : les deux formes sont acceptees partout, par prudence.
 */
@Component
public class HttpTranzakGateway implements TranzakGateway {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final TranzakProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public HttpTranzakGateway(TranzakProperties properties, ObjectMapper objectMapper) {
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
        return properties.isConfigured();
    }

    @Override
    public boolean isLive() {
        return properties.isLive();
    }

    @Override
    public Payment create(NewPayment payment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", payment.amountHtg());
        body.put("currency", "HTG");
        body.put("payment_method", payment.method());
        body.put("customer_name", payment.customerName());
        if (payment.customerPhone() != null) {
            body.put("customer_phone", payment.customerPhone());
        }
        if (payment.customerEmail() != null) {
            body.put("customer_email", payment.customerEmail());
        }
        body.put("reference", payment.reference());
        body.put("description", payment.description());
        if (payment.metadata() != null) {
            body.put("metadata", payment.metadata());
        }
        // Corps envoye serialise, avec sa longueur (pas en flux chunked).
        byte[] json = objectMapper.writeValueAsBytes(body);
        String raw = call(() -> restClient.post()
                .uri(url("/payments"))
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Api-Key", properties.apiKey())
                .body(json)
                .retrieve()
                .body(String.class));
        Payment created = read(raw);
        if (created.transactionId() == null || created.transactionId().isBlank()) {
            throw new TranzakException("Reponse Tranzak sans transaction_id : " + excerpt(raw), null, 200, null);
        }
        return created;
    }

    @Override
    public Payment get(String transactionId) {
        String raw = call(() -> restClient.get()
                .uri(url("/payments/" + segment(transactionId)))
                .header("X-Api-Key", properties.apiKey())
                .retrieve()
                .body(String.class));
        return read(raw);
    }

    @Override
    public Optional<Payment> findByReference(String reference) {
        try {
            String raw = call(() -> restClient.get()
                    .uri(url("/payments/reference/" + segment(reference)))
                    .header("X-Api-Key", properties.apiKey())
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

    private static String segment(String value) {
        return UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }

    /** URL deja encodee : RestClient ne la re-encode pas grace a URI.create. */
    private java.net.URI url(String path) {
        String base = properties.baseUrl();
        return java.net.URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
    }

    private String call(Supplier<String> call) {
        if (!properties.isConfigured()) {
            throw new TranzakException("Tranzak n'est pas configure (TRANZAK_API_KEY)", null, 0, null);
        }
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString();
            String code = null;
            try {
                code = objectMapper.readValue(body, ErrorBody.class).error();
            } catch (RuntimeException ignored) {
                // Corps non JSON : le code reste inconnu.
            }
            throw new TranzakException("Tranzak a repondu " + e.getStatusCode().value() + " : " + excerpt(body),
                    code, e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new TranzakException("Tranzak injoignable ou trop lent : " + e.getMessage(), null, 0, e);
        }
    }

    /** Paiement a la racine (creation) ou sous {@code data} (lecture). */
    Payment read(String raw) {
        try {
            Envelope envelope = objectMapper.readValue(raw, Envelope.class);
            PaymentJson json = envelope.data() != null ? envelope.data()
                    : objectMapper.readValue(raw, PaymentJson.class);
            return json.toPayment();
        } catch (RuntimeException e) {
            throw new TranzakException("Reponse Tranzak illisible : " + excerpt(raw), null, 200, e);
        }
    }

    static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(Boolean success, PaymentJson data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(String error, String message) {
    }

    /** Paiement tel que Tranzak l'ecrit (creation, lecture, webhook). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentJson(
            @JsonProperty("transaction_id") String transactionId,
            String status,
            BigDecimal amount,
            String currency,
            String reference,
            @JsonProperty("payment_url") String paymentUrl,
            @JsonProperty("failure_reason") String failureReason) {

        public Payment toPayment() {
            return new Payment(transactionId, status, amount, currency, reference, paymentUrl, failureReason);
        }
    }
}
