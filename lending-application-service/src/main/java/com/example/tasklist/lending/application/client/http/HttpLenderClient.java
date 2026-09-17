package com.example.tasklist.lending.application.client.http;

import com.example.tasklist.lending.application.client.LenderClient;
import com.example.tasklist.lending.application.config.LenderProperties;
import com.example.tasklist.lending.application.domain.LenderBlockResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.UUID;

/**
 * HTTP-реализация {@link LenderClient}. Реального контракта API лендера у нас нигде нет,
 * поэтому здесь предполагается обычная REST-форма: {@code POST /lenders/{lenderId}/limit-blocks}
 * с {@code requestId} в заголовке {@code Idempotency-Key}, JSON-телом
 * {@code {applicationId, amount}} и JSON-ответом {@code {blockId}}. Поправить под реальный
 * контракт, как только он появится.
 * <p>
 * {@code @Retry} покрывает только транзиентные сбои (сетевые ошибки, 5xx — см.
 * {@code resilience4j.retry.instances.lender} в application.yml); 4xx от лендера считается
 * настоящим бизнес-отказом и не ретраится. {@code @CircuitBreaker} перестаёт долбить упавшего
 * лендера вместо того, чтобы копить зависшие по таймауту потоки.
 */
@Component
public class HttpLenderClient implements LenderClient {

    private final RestClient restClient;

    public HttpLenderClient(RestClient.Builder restClientBuilder, LenderProperties properties) {

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    @CircuitBreaker(name = "lender")
    @Retry(name = "lender")
    public LenderBlockResult blockLimit(UUID lenderId, UUID applicationId, BigDecimal amount, String requestId) {
        BlockLimitResponse response = restClient.post()
                .uri("/lenders/{lenderId}/limit-blocks", lenderId)
                .header("Idempotency-Key", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new BlockLimitRequest(applicationId, amount))
                .retrieve()
                .body(BlockLimitResponse.class);

        if (response == null || response.blockId() == null) {
            throw new IllegalStateException("Lender returned an empty response for application " + applicationId);
        }
        return new LenderBlockResult(response.blockId());
    }
}
