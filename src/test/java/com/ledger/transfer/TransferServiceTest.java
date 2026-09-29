package com.ledger.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ledger.account.Account;
import com.ledger.account.AccountNotFoundException;
import com.ledger.account.AccountRepository;
import com.ledger.account.AccountType;
import com.ledger.ledger.Direction;
import com.ledger.ledger.EntryDraft;
import com.ledger.ledger.InsufficientFundsException;
import com.ledger.transfer.dto.TransferRequest;
import com.ledger.transfer.dto.TransferResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Pure unit tests of the transfer rules: no Spring, no database. */
@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final String CLIENT = "client-1";
    private static final String KEY = "key-1";

    @Mock AccountRepository accounts;
    @Mock TransferRepository transfers;
    @Mock IdempotencyRepository idempotency;

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private TransferService service;

    private final UUID fromId = UUID.randomUUID();
    private final UUID toId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TransferService(accounts, transfers, idempotency, json);
    }

    private Account account(UUID id, AccountType type, String currency, long overdraft) {
        return new Account(id, "acct", currency, type, overdraft, Instant.now());
    }

    private TransferRequest request(long amount) {
        return new TransferRequest(fromId, toId, amount, "USD", "memo");
    }

    /** Stubs a fresh idempotency key plus the two accounts; {@code fromNet} is debits-minus-credits of the source. */
    private void givenFreshTransfer(Account from, Account to, long fromNet) {
        when(idempotency.claim(eq(CLIENT), eq(KEY), anyString())).thenReturn(true);
        when(accounts.findByIdForUpdate(fromId)).thenReturn(Optional.of(from));
        when(accounts.findById(toId)).thenReturn(Optional.of(to));
        when(accounts.debitsMinusCredits(fromId)).thenReturn(fromNet);
        when(transfers.insertTransaction(any(), any())).thenReturn(Instant.parse("2025-01-01T00:00:00Z"));
    }

    @SuppressWarnings("unchecked")
    private List<EntryDraft> capturedEntries() {
        ArgumentCaptor<List<EntryDraft>> captor = ArgumentCaptor.forClass(List.class);
        verify(transfers).insertEntries(any(), captor.capture());
        return captor.getValue();
    }

    @Test
    void assetTransferCreditsTheSourceAndDebitsTheDestination() {
        givenFreshTransfer(account(fromId, AccountType.ASSET, "USD", 0), account(toId, AccountType.ASSET, "USD", 0),
                1000);

        TransferResult result = service.transfer(CLIENT, KEY, request(300));

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.replayed()).isFalse();
        assertThat(capturedEntries()).containsExactly(
                new EntryDraft(fromId, Direction.CREDIT, 300),
                new EntryDraft(toId, Direction.DEBIT, 300));
        verify(idempotency).complete(eq(CLIENT), eq(KEY), eq(201), anyString());
    }

    @Test
    void liabilityTransferDebitsTheSourceAndCreditsTheDestination() {
        // A liability with balance 1000 has net debits-minus-credits of -1000.
        givenFreshTransfer(account(fromId, AccountType.LIABILITY, "USD", 0),
                account(toId, AccountType.LIABILITY, "USD", 0), -1000);

        service.transfer(CLIENT, KEY, request(400));

        assertThat(capturedEntries()).containsExactly(
                new EntryDraft(fromId, Direction.DEBIT, 400),
                new EntryDraft(toId, Direction.CREDIT, 400));
    }

    @Test
    void rejectsATransferThatWouldOverdrawTheSourceAndPostsNothing() {
        when(idempotency.claim(eq(CLIENT), eq(KEY), anyString())).thenReturn(true);
        when(accounts.findByIdForUpdate(fromId)).thenReturn(Optional.of(account(fromId, AccountType.ASSET, "USD", 0)));
        when(accounts.findById(toId)).thenReturn(Optional.of(account(toId, AccountType.ASSET, "USD", 0)));
        when(accounts.debitsMinusCredits(fromId)).thenReturn(100L);

        assertThatThrownBy(() -> service.transfer(CLIENT, KEY, request(101)))
                .isInstanceOf(InsufficientFundsException.class);

        verify(transfers, never()).insertTransaction(any(), any());
        verify(transfers, never()).insertEntries(any(), any());
        verify(idempotency, never()).complete(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void allowsSpendingUpToTheOverdraftLimit() {
        givenFreshTransfer(account(fromId, AccountType.ASSET, "USD", 500), account(toId, AccountType.ASSET, "USD", 0),
                0);

        TransferResult result = service.transfer(CLIENT, KEY, request(500));

        assertThat(result.status()).isEqualTo(201);
    }

    @Test
    void rejectsTransferToTheSameAccountBeforeTouchingTheDatabaseRows() {
        when(idempotency.claim(eq(CLIENT), eq(KEY), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.transfer(CLIENT, KEY,
                new TransferRequest(fromId, fromId, 10L, "USD", null)))
                .isInstanceOf(InvalidTransferException.class);

        verifyNoInteractions(accounts, transfers);
    }

    @Test
    void rejectsCurrencyMismatch() {
        when(idempotency.claim(eq(CLIENT), eq(KEY), anyString())).thenReturn(true);
        when(accounts.findByIdForUpdate(fromId)).thenReturn(Optional.of(account(fromId, AccountType.ASSET, "USD", 0)));
        when(accounts.findById(toId)).thenReturn(Optional.of(account(toId, AccountType.ASSET, "EUR", 0)));

        assertThatThrownBy(() -> service.transfer(CLIENT, KEY, request(10)))
                .isInstanceOf(InvalidTransferException.class)
                .hasMessageContaining("USD");
        verify(transfers, never()).insertEntries(any(), any());
    }

    @Test
    void rejectsTypeMismatch() {
        when(idempotency.claim(eq(CLIENT), eq(KEY), anyString())).thenReturn(true);
        when(accounts.findByIdForUpdate(fromId)).thenReturn(Optional.of(account(fromId, AccountType.ASSET, "USD", 0)));
        when(accounts.findById(toId)).thenReturn(Optional.of(account(toId, AccountType.LIABILITY, "USD", 0)));

        assertThatThrownBy(() -> service.transfer(CLIENT, KEY, request(10)))
                .isInstanceOf(InvalidTransferException.class);
    }

    @Test
    void reportsUnknownSourceAccount() {
        when(idempotency.claim(eq(CLIENT), eq(KEY), anyString())).thenReturn(true);
        when(accounts.findByIdForUpdate(fromId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transfer(CLIENT, KEY, request(10)))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void replayReturnsTheStoredResponseWithoutPostingAgain() throws Exception {
        TransferRequest req = request(300);
        TransferResponse original = new TransferResponse(UUID.randomUUID(), fromId, toId, 300, "USD", "memo",
                Instant.parse("2025-01-01T00:00:00Z"),
                List.of(new TransferResponse.EntryView(fromId, Direction.CREDIT, 300),
                        new TransferResponse.EntryView(toId, Direction.DEBIT, 300)));
        when(idempotency.claim(eq(CLIENT), eq(KEY), anyString())).thenReturn(false);
        when(idempotency.find(CLIENT, KEY)).thenReturn(Optional.of(new IdempotencyRepository.Stored(
                RequestHasher.hash(req), 201, json.writeValueAsString(original))));

        TransferResult result = service.transfer(CLIENT, KEY, req);

        assertThat(result.replayed()).isTrue();
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body()).isEqualTo(original);
        verifyNoInteractions(accounts, transfers);
    }

    @Test
    void reusingAKeyWithADifferentBodyIsAConflict() {
        when(idempotency.claim(eq(CLIENT), eq(KEY), anyString())).thenReturn(false);
        when(idempotency.find(CLIENT, KEY)).thenReturn(Optional.of(
                new IdempotencyRepository.Stored(RequestHasher.hash(request(300)), 201, "{}")));

        assertThatThrownBy(() -> service.transfer(CLIENT, KEY, request(999)))
                .isInstanceOf(IdempotencyConflictException.class);
        verifyNoInteractions(accounts, transfers);
    }
}
