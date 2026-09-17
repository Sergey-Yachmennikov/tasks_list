package com.example.tasklist.kyc.application.client.http;

import com.example.tasklist.kyc.application.client.NotificationClient;
import com.example.tasklist.kyc.application.config.NotificationProperties;
import com.example.tasklist.kyc.application.domain.NotificationResult;
import com.example.tasklist.kyc.application.domain.SmsRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * HTTP-реализация {@link NotificationClient}. Реального контракта SMS-шлюза у нас нигде
 * нет, поэтому здесь предполагается обычная REST-форма: {@code POST /sms} с JSON-телом
 * {@code {clientId, text}}. 422/400 от шлюза (невалидный номер и т.п.) трактуется как
 * {@link NotificationResult.ValidationError} — постоянная ошибка, ретраить бессмысленно;
 * остальное покрывает {@code @Retry}/{@code @CircuitBreaker}, как у {@link HttpKycClient}.
 */
@Component
public class HttpNotificationClient implements NotificationClient {

    private final RestClient restClient;

    public HttpNotificationClient(RestClient.Builder restClientBuilder, NotificationProperties properties) {

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
    @CircuitBreaker(name = "notification")
    @Retry(name = "notification")
    public NotificationResult sendSms(SmsRequest request) {
        try {
            SendSmsResponse response = restClient.post()
                    .uri("/sms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SendSmsRequest(request.clientId(), request.text()))
                    .retrieve()
                    .body(SendSmsResponse.class);

            if (response == null || response.messageId() == null) {
                throw new IllegalStateException("SMS gateway returned an empty response for client " + request.clientId());
            }
            return new NotificationResult.Success(response.messageId());
        } catch (HttpClientErrorException.UnprocessableEntity | HttpClientErrorException.BadRequest ex) {
            return new NotificationResult.ValidationError(ex.getResponseBodyAsString());
        }
    }
}
