package com.ledger.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.account.Account;
import com.ledger.account.AccountClosedException;
import com.ledger.account.AccountNotFoundException;
import com.ledger.account.AccountRepository;
import com.ledger.api.Caller;
import com.ledger.ledger.BalancingRule;
import com.ledger.ledger.EntryDraft;
import com.ledger.ledger.OverdraftRule;
import com.ledger.transfer.dto.TransferRequest;
import com.ledger.transfer.dto.TransferResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final IdempotencyRepository idempotency;
    private final ObjectMapper json;

    public TransferService(AccountRepository accounts, TransferRepository transfers,
                           IdempotencyRepository idempotency, ObjectMapper json) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.json = json;
    }

    /**
     * Everything below happens in ONE database transaction: the idempotency claim, the row lock, the
     * balance check, the journal writes and the stored response. Any exception rolls all of it back,
     * including the idempotency claim, so a failed request can safely be retried with the same key.
     */
    @Transactional
    public TransferResult transfer(Caller caller, String idempotencyKey, TransferRequest req) {
        // Idempotency keys are scoped per caller. The caller must own the source account unless they are an admin.
        String clientId = caller.id();
        String hash = RequestHasher.hash(req);
        if (!idempotency.claim(clientId, idempotencyKey, hash)) {
            return replay(clientId, idempotencyKey, hash);
        }

        TransferResponse response = post(caller, req);

        idempotency.complete(clientId, idempotencyKey, HttpStatus.CREATED.value(), toJson(response));
        return new TransferResult(HttpStatus.CREATED.value(), response, false);
    }

    private TransferResult replay(String clientId, String key, String hash) {
        IdempotencyRepository.Stored stored = idempotency.find(clientId, key)
                .orElseThrow(() -> new IllegalStateException("Idempotency record vanished for key " + key));
        if (!stored.requestHash().equals(hash)) {
            throw new IdempotencyConflictException(key);
        }
        if (stored.body() == null) {
            // Cannot happen: claim and completion commit atomically.
            throw new IllegalStateException("Idempotency record for key " + key + " has no stored response");
        }
        return new TransferResult(stored.status(), fromJson(stored.body()), true);
    }

    private TransferResponse post(Caller caller, TransferRequest req) {
        if (req.fromAccountId().equals(req.toAccountId())) {
            throw new InvalidTransferException("Source and destination accounts must differ");
        }

        // Two locks, and they never deadlock. The source gets NO KEY UPDATE, which serializes transfers out of the same
        // account; it is the only balance this transfer can push below its limit. The destination gets KEY SHARE,
        // which stops it from being closed mid-transfer but blocks no other transfer. The two lock modes do not
        // conflict, so opposite transfers (A to B and B to A) cannot wait on each other. AccountRepository has details.
        //
        // Only the owner (or an admin) can send from an account; to anyone else it looks exactly like a missing one.
        Account from = accounts.findVisibleForTransferSource(req.fromAccountId(), caller)
                .orElseThrow(() -> new AccountNotFoundException(req.fromAccountId()));
        // The destination may belong to anyone: paying another customer is allowed.
        Account to = accounts.findForTransferDestination(req.toAccountId())
                .orElseThrow(() -> new AccountNotFoundException(req.toAccountId()));

        // Checked after both locks are held, so a concurrent close cannot slip in between the check and the posting.
        if (from.isClosed()) {
            throw new AccountClosedException(from.id());
        }
        if (to.isClosed()) {
            throw new AccountClosedException(to.id());
        }

        if (!from.currency().equals(req.currency()) || !to.currency().equals(req.currency())) {
            throw new InvalidTransferException("Both accounts must be denominated in " + req.currency());
        }
        if (from.type() != to.type()) {
            throw new InvalidTransferException("Transfers are only supported between accounts of the same type");
        }

        long amount = req.amount();
        List<EntryDraft> entries = List.of(
                new EntryDraft(from.id(), from.type().decreaseSide(), amount),
                new EntryDraft(to.id(), to.type().increaseSide(), amount));
        BalancingRule.check(entries);

        // The balance is read AFTER the lock is held, so no other transfer can change it before we commit.
        long balance = from.type().balanceFrom(accounts.debitsMinusCredits(from.id()));
        OverdraftRule.check(balance, from.overdraftLimit(), amount);

        UUID txId = UUID.randomUUID();
        Instant createdAt = transfers.insertTransaction(txId, req.description(), caller);
        transfers.insertEntries(txId, entries);

        return new TransferResponse(txId, from.id(), to.id(), amount, req.currency(), req.description(), createdAt,
                entries.stream()
                        .map(e -> new TransferResponse.EntryView(e.accountId(), e.direction(), e.amount()))
                        .toList());
    }

    private String toJson(TransferResponse response) {
        try {
            return json.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private TransferResponse fromJson(String body) {
        try {
            return json.readValue(body, TransferResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
