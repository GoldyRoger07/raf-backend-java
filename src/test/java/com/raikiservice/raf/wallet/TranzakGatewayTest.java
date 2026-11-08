package com.raikiservice.raf.wallet;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.raikiservice.raf.wallet.tranzak.HttpTranzakGateway;
import com.raikiservice.raf.wallet.tranzak.TranzakException;
import com.raikiservice.raf.wallet.tranzak.TranzakGateway.NewPayment;
import com.raikiservice.raf.wallet.tranzak.TranzakGateway.Payment;
import com.raikiservice.raf.wallet.tranzak.TranzakProperties;
import com.raikiservice.raf.wallet.tranzak.TranzakSignature;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Client Tranzak face a un faux serveur local, aux formats de docs.tranzak.co. Aucun appel reel. */
class TranzakGatewayTest {

    private HttpServer server;
    private HttpTranzakGateway gateway;
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedKey = new AtomicReference<>();
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedPath.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            receivedKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/gateway/v1";
        gateway = new HttpTranzakGateway(new TranzakProperties("tk_test_abc", "whsec_x", base, Duration.ofSeconds(5), null, null),
                JsonMapper.builder().build());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void createsAPaymentAndReadsTheRootShape() {
        status = 201;
        reply = """
                {"success":true,"transaction_id":"17843268616389","amount":"524.00","currency":"HTG",
                 "status":"processing","created_at":"2026-10-04T14:00:00.000000Z",
                 "payment_url":"https://moncash.example/pay/abc"}""";

        Payment payment = gateway.create(new NewPayment(524, "moncash", "Jean Dupont", "+50937654321", null,
                "RAF-TOPUP-7", "Recharge RAF", Map.of("topup_id", 7)));

        assertThat(receivedPath.get()).isEqualTo("POST /api/gateway/v1/payments");
        assertThat(receivedKey.get()).isEqualTo("tk_test_abc");
        assertThat(receivedBody.get()).contains("\"amount\":524", "\"currency\":\"HTG\"",
                "\"payment_method\":\"moncash\"", "\"reference\":\"RAF-TOPUP-7\"", "\"customer_name\":\"Jean Dupont\"");
        assertThat(receivedBody.get()).doesNotContain("customer_email");
        assertThat(payment.transactionId()).isEqualTo("17843268616389");
        assertThat(payment.paymentUrl()).isEqualTo("https://moncash.example/pay/abc");
        assertThat(payment.amount()).isEqualByComparingTo(new BigDecimal("524"));
    }

    @Test
    void readsAPaymentUnderData() {
        reply = """
                {"success":true,"data":{"transaction_id":"17843268616389","amount":"500.00","currency":"HTG",
                 "status":"completed","payment_method":"moncash","reference":"RAF-TOPUP-7",
                 "customer":{"name":"Jean Dupont"},"failed_at":null,"failure_reason":null,"metadata":{"topup_id":7}}}""";

        Payment payment = gateway.get("17843268616389");

        assertThat(receivedPath.get()).isEqualTo("GET /api/gateway/v1/payments/17843268616389");
        assertThat(payment.completed()).isTrue();
        assertThat(payment.reference()).isEqualTo("RAF-TOPUP-7");
    }

    @Test
    void anUnknownReferenceIsEmptyAndErrorsKeepTheirCode() {
        status = 404;
        reply = "{\"success\":false,\"error\":\"transaction_not_found\",\"message\":\"Transaction not found\"}";
        assertThat(gateway.findByReference("RAF-TOPUP-8")).isEmpty();
        assertThat(receivedPath.get()).isEqualTo("GET /api/gateway/v1/payments/reference/RAF-TOPUP-8");

        status = 400;
        reply = "{\"success\":false,\"error\":\"amount_too_low\",\"message\":\"Minimum 20 HTG\"}";
        assertThatThrownBy(() -> gateway.create(new NewPayment(5, "natcash", "X", null, null, "R", "D", null)))
                .isInstanceOf(TranzakException.class)
                .satisfies(e -> {
                    assertThat(((TranzakException) e).getCode()).isEqualTo("amount_too_low");
                    assertThat(((TranzakException) e).getHttpStatus()).isEqualTo(400);
                });
    }

    @Test
    void signatureIsTheHexHmacOfTheRawBody() {
        byte[] body = "{\"event\":\"payment.success\"}".getBytes(StandardCharsets.UTF_8);
        String signature = TranzakSignature.sign("whsec_x", body);

        assertThat(signature).hasSize(64).matches("[0-9a-f]+");
        assertThat(TranzakSignature.verify("whsec_x", body, signature)).isTrue();
        assertThat(TranzakSignature.verify("whsec_x", body, signature.toUpperCase())).isTrue();
        assertThat(TranzakSignature.verify("whsec_y", body, signature)).isFalse();
        assertThat(TranzakSignature.verify("", body, signature)).isFalse();
        assertThat(TranzakSignature.verify("whsec_x", body, null)).isFalse();
    }
}
