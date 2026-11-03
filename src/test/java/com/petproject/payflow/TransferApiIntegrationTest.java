package com.petproject.payflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petproject.payflow.api.dto.AccountResponse;
import com.petproject.payflow.api.dto.TransferResponse;
import com.petproject.payflow.domain.TransferStatus;
import com.petproject.payflow.messaging.OutboxPublisher;
import com.petproject.payflow.messaging.TransferEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@Import(TransferApiIntegrationTest.KafkaProbeConfiguration.class)
class TransferApiIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @SuppressWarnings("deprecation")
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.outbox.poll-interval-ms", () -> 60_000);
        registry.add("app.outbox.initial-delay-ms", () -> 60_000);
        registry.add("app.rate-limit.transfers-per-minute", () -> 1000);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private KafkaProbe probe;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Test
    void transferIsIdempotentAndPublishesKafkaEvent() {
        UUID alice = createAccount("Alice", "USD", "100.00");
        UUID bob = createAccount("Bob", "USD", "50.00");
        String idempotencyKey = "it-" + UUID.randomUUID();

        ResponseEntity<TransferResponse> first = transfer(idempotencyKey, alice, bob, "40.00");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TransferResponse created = first.getBody();
        assertThat(created).isNotNull();
        assertThat(created.status()).isEqualTo(TransferStatus.COMPLETED);

        ResponseEntity<TransferResponse> replay = transfer(idempotencyKey, alice, bob, "40.00");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().id()).isEqualTo(created.id());

        assertThat(balanceOf(alice)).isEqualByComparingTo("60.00");
        assertThat(balanceOf(bob)).isEqualByComparingTo("90.00");

        // The scheduler is effectively disabled in tests, so publish deterministically.
        outboxPublisher.publishPendingEvents();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(probe.events()).anyMatch(event ->
                        event.transferId().equals(created.id())
                                && TransferEvent.TRANSFER_COMPLETED.equals(event.eventType())));
    }

    @Test
    void insufficientFundsReturns422AndPersistsFailedTransfer() {
        UUID alice = createAccount("Alice", "USD", "10.00");
        UUID bob = createAccount("Bob", "USD", "0.00");
        String idempotencyKey = "it-" + UUID.randomUUID();

        ResponseEntity<JsonNode> response = rest.exchange("/api/v1/transfers", HttpMethod.POST,
                transferEntity(idempotencyKey, alice, bob, "50.00"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("reason").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(response.getBody().path("status").asInt()).isEqualTo(422);
        assertThat(response.getBody().path("transferId").asText()).isNotBlank();

        UUID failedTransferId = UUID.fromString(response.getBody().path("transferId").asText());
        JsonNode persisted = rest.getForObject("/api/v1/transfers/" + failedTransferId, JsonNode.class);
        assertThat(persisted).isNotNull();
        assertThat(persisted.path("status").asText()).isEqualTo("FAILED");
        assertThat(balanceOf(alice)).isEqualByComparingTo("10.00");
        assertThat(balanceOf(bob)).isEqualByComparingTo("0.00");
    }

    private UUID createAccount(String ownerName, String currency, String initialBalance) {
        String email = ownerName.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<AccountResponse> response = rest.postForEntity("/api/v1/accounts",
                Map.of("ownerName", ownerName,
                        "email", email,
                        "currency", currency,
                        "initialBalance", new BigDecimal(initialBalance)),
                AccountResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().id();
    }

    private ResponseEntity<TransferResponse> transfer(String idempotencyKey, UUID source, UUID target,
                                                      String amount) {
        return rest.exchange("/api/v1/transfers", HttpMethod.POST,
                transferEntity(idempotencyKey, source, target, amount), TransferResponse.class);
    }

    private HttpEntity<Map<String, Object>> transferEntity(String idempotencyKey, UUID source, UUID target,
                                                           String amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return new HttpEntity<>(Map.of(
                "sourceAccountId", source.toString(),
                "targetAccountId", target.toString(),
                "amount", new BigDecimal(amount)), headers);
    }

    private BigDecimal balanceOf(UUID accountId) {
        AccountResponse account = rest.getForObject("/api/v1/accounts/" + accountId, AccountResponse.class);
        assertThat(account).isNotNull();
        return account.balance();
    }

    static class KafkaProbe {

        private final ObjectMapper objectMapper;
        private final List<TransferEvent> events = new CopyOnWriteArrayList<>();

        KafkaProbe(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @KafkaListener(topics = "${app.kafka.transfer-topic}", groupId = "payflow-it-probe")
        void onEvent(String payload) throws Exception {
            events.add(objectMapper.readValue(payload, TransferEvent.class));
        }

        List<TransferEvent> events() {
            return events;
        }
    }

    @TestConfiguration
    static class KafkaProbeConfiguration {

        @Bean
        KafkaProbe kafkaProbe(ObjectMapper objectMapper) {
            return new KafkaProbe(objectMapper);
        }
    }
}
