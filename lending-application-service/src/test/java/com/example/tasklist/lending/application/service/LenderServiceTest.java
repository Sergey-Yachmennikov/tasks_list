package com.example.tasklist.lending.application.service;

import com.example.tasklist.lending.application.client.LenderClient;
import com.example.tasklist.lending.application.domain.Application;
import com.example.tasklist.lending.application.domain.ApplicationStatus;
import com.example.tasklist.lending.application.domain.LenderBlockResult;
import com.example.tasklist.lending.application.outbox.OutboxMessage;
import com.example.tasklist.lending.application.outbox.OutboxRepository;
import com.example.tasklist.lending.application.repository.ApplicationRepository;
import com.example.tasklist.lending.application.service.exception.ApplicationNotFoundException;
import com.example.tasklist.lending.application.service.exception.InvalidApplicationStatusException;
import com.example.tasklist.lending.application.service.exception.LenderBlockingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LenderServiceTest {

    @Mock
    private ApplicationRepository applicationRepository;
    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private LenderClient lenderClient;

    private LenderService lenderService;

    private final UUID applicationId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();
    private final UUID lenderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // A mocked PlatformTransactionManager is enough here: TransactionTemplate only needs
        // getTransaction()/commit() to be callable, and the callback never inspects the status,
        // so the mock's default null return for getTransaction() is fine.
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        lenderService = new LenderService(
                applicationRepository,
                outboxRepository,
                lenderClient,
                new ObjectMapper().findAndRegisterModules(),
                transactionManager,
                new SimpleMeterRegistry()
        );
    }

    private Application applicationWithStatus(ApplicationStatus status) {
        return new Application(applicationId, clientId, lenderId, status, new BigDecimal("1000.00"), Optional.empty());
    }

    @Test
    void blocksLimitAndWritesOutboxMessage_whenApprovedAndLenderSucceeds() {
        when(applicationRepository.findById(applicationId))
                .thenReturn(Optional.of(applicationWithStatus(ApplicationStatus.SCORING_APPROVED)));
        when(lenderClient.blockLimit(eq(lenderId), eq(applicationId), any(), anyString()))
                .thenReturn(new LenderBlockResult("block-123"));
        when(applicationRepository.compareAndSetLimitBlocked(applicationId, ApplicationStatus.SCORING_APPROVED, "block-123"))
                .thenReturn(true);

        lenderService.blockLenderLimit(applicationId);

        verify(lenderClient).blockLimit(lenderId, applicationId, new BigDecimal("1000.00"), "app-limit-block:" + applicationId);
        verify(applicationRepository).compareAndSetLimitBlocked(applicationId, ApplicationStatus.SCORING_APPROVED, "block-123");

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxRepository).save(captor.capture());
        OutboxMessage saved = captor.getValue();
        assertThat(saved.aggregateId()).isEqualTo(applicationId);
        assertThat(saved.topic()).isEqualTo("application-limit-blocked");
        assertThat(saved.messageKey()).isEqualTo(applicationId.toString());
        assertThat(saved.payload()).contains("block-123");
    }

    @Test
    void isNoOp_whenAlreadyBlocked() {
        when(applicationRepository.findById(applicationId))
                .thenReturn(Optional.of(applicationWithStatus(ApplicationStatus.LIMIT_BLOCKED)));

        lenderService.blockLenderLimit(applicationId);

        verify(lenderClient, never()).blockLimit(any(), any(), any(), anyString());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void throws_whenStatusIsNotScoringApproved() {
        when(applicationRepository.findById(applicationId))
                .thenReturn(Optional.of(applicationWithStatus(ApplicationStatus.INITIAL)));

        assertThatThrownBy(() -> lenderService.blockLenderLimit(applicationId))
                .isInstanceOf(InvalidApplicationStatusException.class);

        verify(lenderClient, never()).blockLimit(any(), any(), any(), anyString());
    }

    @Test
    void throws_whenApplicationNotFound() {
        when(applicationRepository.findById(applicationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lenderService.blockLenderLimit(applicationId))
                .isInstanceOf(ApplicationNotFoundException.class);
    }

    @Test
    void wrapsLenderFailure() {
        when(applicationRepository.findById(applicationId))
                .thenReturn(Optional.of(applicationWithStatus(ApplicationStatus.SCORING_APPROVED)));
        when(lenderClient.blockLimit(any(), any(), any(), anyString()))
                .thenThrow(new RuntimeException("lender timeout"));

        assertThatThrownBy(() -> lenderService.blockLenderLimit(applicationId))
                .isInstanceOf(LenderBlockingException.class)
                .hasCauseInstanceOf(RuntimeException.class);

        verify(applicationRepository, never()).compareAndSetLimitBlocked(any(), any(), any());
    }

    @Test
    void doesNotWriteOutbox_whenLostRaceToConcurrentUpdate() {
        when(applicationRepository.findById(applicationId))
                .thenReturn(Optional.of(applicationWithStatus(ApplicationStatus.SCORING_APPROVED)));
        when(lenderClient.blockLimit(any(), any(), any(), anyString()))
                .thenReturn(new LenderBlockResult("block-123"));
        when(applicationRepository.compareAndSetLimitBlocked(applicationId, ApplicationStatus.SCORING_APPROVED, "block-123"))
                .thenReturn(false);

        lenderService.blockLenderLimit(applicationId);

        verify(outboxRepository, never()).save(any());
    }
}
