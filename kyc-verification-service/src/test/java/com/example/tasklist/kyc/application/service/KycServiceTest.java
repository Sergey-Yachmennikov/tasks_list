package com.example.tasklist.kyc.application.service;

import com.example.tasklist.kyc.application.client.KycClient;
import com.example.tasklist.kyc.application.client.NotificationClient;
import com.example.tasklist.kyc.application.domain.Application;
import com.example.tasklist.kyc.application.domain.ApplicationStatus;
import com.example.tasklist.kyc.application.domain.KycResult;
import com.example.tasklist.kyc.application.domain.NotificationResult;
import com.example.tasklist.kyc.application.domain.SmsRequest;
import com.example.tasklist.kyc.application.repository.ApplicationRepository;
import com.example.tasklist.kyc.application.service.exception.ApplicationClientMismatchException;
import com.example.tasklist.kyc.application.service.exception.ApplicationNotFoundException;
import com.example.tasklist.kyc.application.service.exception.InvalidApplicationStatusException;
import com.example.tasklist.kyc.application.service.exception.KycNotCompletedException;
import com.example.tasklist.kyc.application.service.exception.NotificationFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KycServiceTest {

    @Mock
    private ApplicationRepository applicationRepository;
    @Mock
    private KycClient kycClient;
    @Mock
    private NotificationClient notificationClient;

    private KycService kycService;

    private final UUID applicationId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();
    private static final String KYC_SESSION_ID = "session-1";

    @BeforeEach
    void setUp() {
        kycService = new KycService(applicationRepository, kycClient, notificationClient);
    }

    private Application applicationWithStatus(ApplicationStatus status) {
        return new Application(applicationId, clientId, status, KYC_SESSION_ID);
    }

    @Test
    void completesKyc_whenPendingAndKycConfirmedAndSmsSucceeds() {
        when(applicationRepository.findById(applicationId))
                .thenReturn(Optional.of(applicationWithStatus(ApplicationStatus.KYC_PENDING)));
        when(kycClient.fetchStatus(KYC_SESSION_ID)).thenReturn(new KycResult(true, "verified"));
        when(notificationClient.sendSms(any())).thenReturn(new NotificationResult.Success("msg-1"));
        when(applicationRepository.compareAndSetKycCompleted(applicationId, ApplicationStatus.KYC_PENDING))
                .thenReturn(true);

        Application result = kycService.completeKyc(applicationId, clientId);

        assertThat(result.status()).isEqualTo(ApplicationStatus.KYC_COMPLETED);
        verify(notificationClient).sendSms(new SmsRequest(clientId.toString(), "Your identity verification is complete."));
        verify(applicationRepository).compareAndSetKycCompleted(applicationId, ApplicationStatus.KYC_PENDING);
    }

    @Test
    void isNoOp_whenAlreadyCompleted() {
        when(applicationRepository.findById(applicationId))
                .thenReturn(Optional.of(applicationWithStatus(ApplicationStatus.KYC_COMPLETED)));

        Application result = kycService.completeKyc(applicationId, clientId);

        assertThat(result.status()).isEqualTo(ApplicationStatus.KYC_COMPLETED);
        verify(kycClient, never()).fetchStatus(anyString());
        verify(notificationClient, never()).sendSms(any());
        verify(applicationRepository, never()).compareAndSetKycCompleted(any(), any());
    }

    @Test
    void throws_whenStatusIsNotKycPending() {
        when(applicationRepository.findById(applicationId))
                .thenReturn(Optional.of(applicationWithStatus(ApplicationStatus.INITIAL)));

        assertThatThrownBy(() -> kycService.completeKyc(applicationId, clientId))
                .isInstanceOf(InvalidApplicationStatusException.class);

        verify(kycClient, never()).fetchStatus(anyString());
    }

    @Test
    void throws_whenApplicationNotFound() {
        when(applicationRepository.findById(applicationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> kycService.completeKyc(applicationId, clientId))
                .isInstanceOf(ApplicationNotFoundException.class);
    }

    @Test
    void throws_whenClientIdDoesNotMatch() {
        when(applicationRepository.findById(applicationId))
                .thenReturn(Optional.of(applicationWithStatus(ApplicationStatus.KYC_PENDING)));

        UUID someoneElse = UUID.randomUUID();
        assertThatThrownBy(() -> kycService.completeKyc(applicationId, someoneElse))
                .isInstanceOf(ApplicationClientMismatchException.class);

        verify(kycClient, never()).fetchStatus(anyString());
    }

    @Test
    void throws_whenKycNotCompletedAtProvider() {
        when(applicationRepository.findById(applicationId))
                .thenReturn(Optional.of(applicationWithStatus(ApplicationStatus.KYC_PENDING)));
        when(kycClient.fetchStatus(KYC_SESSION_ID)).thenReturn(new KycResult(false, "still processing"));

        assertThatThrownBy(() -> kycService.completeKyc(applicationId, clientId))
                .isInstanceOf(KycNotCompletedException.class);

        verify(notificationClient, never()).sendSms(any());
        verify(applicationRepository, never()).compareAndSetKycCompleted(any(), any());
    }

    @Test
    void throws_andLeavesStatusUntouched_whenNotificationFails() {
        when(applicationRepository.findById(applicationId))
                .thenReturn(Optional.of(applicationWithStatus(ApplicationStatus.KYC_PENDING)));
        when(kycClient.fetchStatus(KYC_SESSION_ID)).thenReturn(new KycResult(true, "verified"));
        when(notificationClient.sendSms(any())).thenReturn(new NotificationResult.ValidationError("bad phone number"));

        assertThatThrownBy(() -> kycService.completeKyc(applicationId, clientId))
                .isInstanceOf(NotificationFailedException.class);

        verify(applicationRepository, never()).compareAndSetKycCompleted(any(), any());
    }

    @Test
    void returnsCompleted_whenLostRaceToConcurrentUpdate() {
        when(applicationRepository.findById(applicationId))
                .thenReturn(Optional.of(applicationWithStatus(ApplicationStatus.KYC_PENDING)));
        when(kycClient.fetchStatus(KYC_SESSION_ID)).thenReturn(new KycResult(true, "verified"));
        when(notificationClient.sendSms(any())).thenReturn(new NotificationResult.Success("msg-1"));
        when(applicationRepository.compareAndSetKycCompleted(applicationId, ApplicationStatus.KYC_PENDING))
                .thenReturn(false);

        Application result = kycService.completeKyc(applicationId, clientId);

        assertThat(result.status()).isEqualTo(ApplicationStatus.KYC_COMPLETED);
    }
}
