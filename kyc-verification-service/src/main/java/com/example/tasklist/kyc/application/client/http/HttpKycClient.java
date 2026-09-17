package com.example.tasklist.kyc.application.client.http;

import com.example.tasklist.kyc.application.client.KycClient;
import com.example.tasklist.kyc.application.config.KycProperties;
import com.example.tasklist.kyc.application.domain.KycResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * HTTP-реализация {@link KycClient}. Реального контракта провайдера KYC у нас нигде нет,
 * поэтому здесь предполагается обычная REST-форма: {@code GET /kyc-sessions/{kycSessionId}}
 * с JSON-ответом {@code {completed, details}}. Поправить под реальный контракт, как только
 * он появится.
 * <p>
 * {@code @Retry} покрывает только транзиентные сбои (сетевые ошибки, 5xx — см.
 * {@code resilience4j.retry.instances.kyc} в application.yml); 4xx от провайдера считается
 * настоящим бизнес-отказом и не ретраится. {@code @CircuitBreaker} перестаёт долбить
 * упавшего провайдера вместо того, чтобы копить зависшие по таймауту потоки.
 */
@Component
public class HttpKycClient implements KycClient {

    private final RestClient restClient;

    public HttpKycClient(RestClient.Builder restClientBuilder, KycProperties properties) {

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
    @CircuitBreaker(name = "kyc")
    @Retry(name = "kyc")
    public KycResult fetchStatus(String kycSessionId) {
        KycStatusResponse response = restClient.get()
                .uri("/kyc-sessions/{kycSessionId}", kycSessionId)
                .retrieve()
                .body(KycStatusResponse.class);

        if (response == null) {
            throw new IllegalStateException("KYC provider returned an empty response for session " + kycSessionId);
        }
        return new KycResult(response.completed(), response.details());
    }
}
