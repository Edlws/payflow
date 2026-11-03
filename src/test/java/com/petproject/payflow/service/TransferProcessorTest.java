package com.petproject.payflow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.petproject.payflow.api.dto.TransferRequest;
import com.petproject.payflow.api.error.AccountNotFoundException;
import com.petproject.payflow.api.error.SameAccountTransferException;
import com.petproject.payflow.domain.Account;
import com.petproject.payflow.domain.AccountStatus;
import com.petproject.payflow.domain.Transfer;
import com.petproject.payflow.domain.TransferStatus;
import com.petproject.payflow.messaging.OutboxWriter;
import com.petproject.payflow.repository.AccountRepository;
import com.petproject.payflow.repository.TransferRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TransferProcessorTest {

    private static final String IDEMPOTENCY_KEY = "test-key-0001";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private OutboxWriter outboxWriter;

    @InjectMocks
    private TransferProcessor processor;

    @Test
    void completesTransferAndWritesOutboxEvent() {
        Account source = Account.create("Alice", "alice@example.com", "USD", new BigDecimal("100.00"));
        Account target = Account.create("Bob", "bob@example.com", "USD", new BigDecimal("50.00"));
        stubAccounts(source, target);
        when(transferRepository.saveAndFlush(any(Transfer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransferOutcome outcome = processor.execute(IDEMPOTENCY_KEY, request(source, target, "30.00"));

        assertThat(outcome.transfer().getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(outcome.transfer().getCompletedAt()).isNotNull();
        assertThat(source.getBalance()).isEqualByComparingTo("70.00");
        assertThat(target.getBalance()).isEqualByComparingTo("80.00");
        verify(outboxWriter).appendTransferEvent(outcome.transfer());
    }

    @Test
    void rejectsTransferWithInsufficientFundsButPersistsFailedTransfer() {
        Account source = Account.create("Alice", "alice@example.com", "USD", new BigDecimal("10.00"));
        Account target = Account.create("Bob", "bob@example.com", "USD", new BigDecimal("50.00"));
        stubAccounts(source, target);
        when(transferRepository.saveAndFlush(any(Transfer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransferOutcome outcome = processor.execute(IDEMPOTENCY_KEY, request(source, target, "30.00"));

        assertThat(outcome.transfer().getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(outcome.transfer().getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(source.getBalance()).isEqualByComparingTo("10.00");
        assertThat(target.getBalance()).isEqualByComparingTo("50.00");
        verify(outboxWriter).appendTransferEvent(outcome.transfer());
    }

    @Test
    void rejectsBlockedTargetAccount() {
        Account source = Account.create("Alice", "alice@example.com", "USD", new BigDecimal("100.00"));
        Account target = Account.create("Bob", "bob@example.com", "USD", new BigDecimal("50.00"));
        ReflectionTestUtils.setField(target, "status", AccountStatus.BLOCKED);
        stubAccounts(source, target);
        when(transferRepository.saveAndFlush(any(Transfer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransferOutcome outcome = processor.execute(IDEMPOTENCY_KEY, request(source, target, "10.00"));

        assertThat(outcome.transfer().getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(outcome.transfer().getFailureReason()).isEqualTo("TARGET_NOT_ACTIVE");
        assertThat(source.getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void rejectsCurrencyMismatch() {
        Account source = Account.create("Alice", "alice@example.com", "USD", new BigDecimal("100.00"));
        Account target = Account.create("Eve", "eve@example.com", "EUR", new BigDecimal("50.00"));
        stubAccounts(source, target);
        when(transferRepository.saveAndFlush(any(Transfer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TransferOutcome outcome = processor.execute(IDEMPOTENCY_KEY, request(source, target, "10.00"));

        assertThat(outcome.transfer().getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(outcome.transfer().getFailureReason()).isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    void rejectsTransferToTheSameAccountWithoutTouchingTheDatabase() {
        UUID accountId = UUID.randomUUID();

        assertThatThrownBy(() -> processor.execute(IDEMPOTENCY_KEY,
                new TransferRequest(accountId, accountId, new BigDecimal("10.00"))))
                .isInstanceOf(SameAccountTransferException.class);

        verifyNoInteractions(accountRepository, transferRepository);
    }

    @Test
    void reportsMissingAccount() {
        Account source = Account.create("Alice", "alice@example.com", "USD", new BigDecimal("100.00"));
        when(accountRepository.findAllForUpdate(anyCollection())).thenReturn(List.of(source));

        assertThatThrownBy(() -> processor.execute(IDEMPOTENCY_KEY,
                new TransferRequest(source.getId(), UUID.randomUUID(), new BigDecimal("10.00"))))
                .isInstanceOf(AccountNotFoundException.class);

        verifyNoInteractions(transferRepository);
    }

    private void stubAccounts(Account source, Account target) {
        when(accountRepository.findAllForUpdate(anyCollection()))
                .thenReturn(List.of(source, target));
    }

    private static TransferRequest request(Account source, Account target, String amount) {
        return new TransferRequest(source.getId(), target.getId(), new BigDecimal(amount));
    }
}
