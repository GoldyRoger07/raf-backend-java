package com.raikiservice.raf.wallet;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.raikiservice.raf.wallet.tranzak.HttpTranzakPayoutGateway;
import com.raikiservice.raf.wallet.tranzak.TranzakException;
import com.raikiservice.raf.wallet.tranzak.TranzakPayoutGateway.Authorization;
import com.raikiservice.raf.wallet.tranzak.TranzakPayoutGateway.NewPayout;
import com.raikiservice.raf.wallet.tranzak.TranzakPayoutGateway.Payout;
import com.raikiservice.raf.wallet.tranzak.TranzakProperties;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Client payouts Tranzak face a un faux serveur local, aux exemples de docs.tranzak.co/payouts. */
class TranzakPayoutGatewayTest {

    private HttpServer server;
    private HttpTranzakPayoutGateway gateway;
    private final Map<String, String> replies = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private volatile String key;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            key = exchange.getRequestHeaders().getFirst("X-API-Key");
            bodies.put(route, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = replies.getOrDefault(route, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statuses.getOrDefault(route, 200), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/treasury/v1";
        gateway = new HttpTranzakPayoutGateway(new TranzakProperties("tk_test_x", "whsec_x", "http://unused",
                Duration.ofSeconds(5), "tk_live_abc", base), JsonMapper.builder().build());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void authorizesThenSends() {
        statuses.put("POST /api/treasury/v1/payouts/authorize", 201);
        replies.put("POST /api/treasury/v1/payouts/authorize", """
                {"success":true,"authorization":{"authorization_id":"AUTH_9F3A","authorization_token":"ptk_8s7d",
                 "expires_at":"2026-10-13T14:35:00.000000Z","reference":"RAF-WD-7-1","network":"moncash",
                 "receiver":"50937000000","amount":966,"fee":33.81,"fee_percent":3.5,"total_debited":999.81,
                 "currency":"HTG"}}""");
        statuses.put("POST /api/treasury/v1/payouts", 202);
        replies.put("POST /api/treasury/v1/payouts", """
                {"success":true,"duplicate":false,"payout":{"payout_id":"PAY_3B1D","reference":"RAF-WD-7-1",
                 "network":"moncash","receiver":"50937000000","amount":966,"fee":33.81,"total_debited":999.81,
                 "currency":"HTG","status":"processing","failure_reason":null,
                 "created_at":"2026-10-13T14:31:02.000000Z","completed_at":null}}""");

        Authorization authorization = gateway.authorize(
                new NewPayout(966, "moncash", "50937000000", "RAF-WD-7-1", "Retrait RAF #7"));
        assertThat(authorization.token()).isEqualTo("ptk_8s7d");
        assertThat(authorization.totalDebited()).isEqualByComparingTo(new BigDecimal("999.81"));
        assertThat(key).isEqualTo("tk_live_abc");
        assertThat(bodies.get("POST /api/treasury/v1/payouts/authorize")).contains("\"amount\":966",
                "\"network\":\"moncash\"", "\"receiver\":\"50937000000\"", "\"reference\":\"RAF-WD-7-1\"");

        Payout payout = gateway.send("ptk_8s7d");
        assertThat(bodies.get("POST /api/treasury/v1/payouts")).isEqualTo("{\"authorization_token\":\"ptk_8s7d\"}");
        assertThat(payout.payoutId()).isEqualTo("PAY_3B1D");
        assertThat(payout.status()).isEqualTo("processing");
    }

    @Test
    void readsAFailedPayoutAndPayoutErrors() {
        replies.put("GET /api/treasury/v1/payouts/PAY_7C2E", """
                {"success":true,"data":{"payout_id":"PAY_7C2E","reference":"RAF-WD-8-1","status":"failed",
                 "amount":500,"fee":17.5,"failure_code":"receiver_not_found","failure_message":"Check the number.",
                 "retryable":true,"refund_status":"refunded","total_refunded":517.5}}""");
        Payout failed = gateway.get("PAY_7C2E");
        assertThat(failed.failed()).isTrue();
        assertThat(failed.failureCode()).isEqualTo("receiver_not_found");

        statuses.put("GET /api/treasury/v1/payouts/reference/RAF-WD-9-1", 404);
        replies.put("GET /api/treasury/v1/payouts/reference/RAF-WD-9-1",
                "{\"success\":false,\"error\":\"payout_not_found\",\"message\":\"Not found\"}");
        assertThat(gateway.findByReference("RAF-WD-9-1")).isEmpty();

        statuses.put("POST /api/treasury/v1/payouts/authorize", 402);
        replies.put("POST /api/treasury/v1/payouts/authorize", """
                {"success":false,"error":"insufficient_wallet_balance","message":"Not available",
                 "network":"moncash","required":1040.0,"available":50.0}""");
        assertThatThrownBy(() -> gateway.authorize(new NewPayout(1000, "moncash", "50937000000", "R", "D")))
                .isInstanceOf(TranzakException.class)
                .satisfies(e -> assertThat(((TranzakException) e).getCode()).isEqualTo("insufficient_wallet_balance"));
    }

    @Test
    void aTestKeyIsNotEnoughForPayouts() {
        HttpTranzakPayoutGateway withTestKey = new HttpTranzakPayoutGateway(new TranzakProperties("tk_test_x",
                "whsec_x", "http://unused", Duration.ofSeconds(5), "tk_test_x", "http://127.0.0.1:9"),
                JsonMapper.builder().build());
        assertThat(withTestKey.isConfigured()).isFalse();
        assertThat(gateway.isConfigured()).isTrue();
    }
}
