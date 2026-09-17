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
 * HTTP-based {@link LenderClient}. The lender's actual API contract isn't specified anywhere
 * we own, so this assumes a conventional REST shape: {@code POST /lenders/{lenderId}/limit-blocks}
 * with the {@code requestId} carried as an {@code Idempotency-Key} header, JSON body
 * {@code {applicationId, amount}}, JSON response {@code {blockId}}. Adjust to match the real
 * contract once it exists.
 * <p>
 * {@code @Retry} only covers transient failures (network errors, 5xx — see
 * {@code resilience4j.retry.instances.lender} in application.yml); a 4xx from the lender is
 * treated as a genuine business rejection and is not retried. {@code @CircuitBreaker} stops
 * hammering a lender that's down instead of piling up timed-out threads.
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
