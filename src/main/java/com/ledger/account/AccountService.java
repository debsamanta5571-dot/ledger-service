package com.ledger.account;

import com.ledger.account.dto.AccountResponse;
import com.ledger.account.dto.CreateAccountRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public AccountResponse create(CreateAccountRequest req) {
        long overdraft = req.overdraftLimit() == null ? 0L : req.overdraftLimit();
        Account saved = accounts.insert(new Account(
                UUID.randomUUID(), req.name().strip(), req.currency(), req.type(), overdraft, Instant.now(), null));
        return AccountResponse.of(saved, 0L, 0L);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(int limit, boolean includeClosed) {
        return accounts.findRecentWithNet(limit, includeClosed).stream()
                .map(a -> AccountResponse.of(a.account(), a.account().type().balanceFrom(a.debitsMinusCredits()),
                        a.entryCount()))
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(UUID id) {
        Account account = accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        long balance = account.type().balanceFrom(accounts.debitsMinusCredits(id));
        return AccountResponse.of(account, balance, accounts.entryCount(id));
    }

    /**
     * "Removes" an account by closing it; nothing is deleted (see V5__account_closing.sql). Only a zero balance can
     * be closed, so no money is stranded. Closing an already-closed account is a no-op, so DELETE stays idempotent.
     */
    @Transactional
    public void close(UUID id) {
        Account account = accounts.findByIdForClose(id).orElseThrow(() -> new AccountNotFoundException(id));
        if (account.isClosed()) {
            return;
        }
        // Read under the exclusive lock: no transfer can add or remove an entry for this account until we commit.
        long balance = account.type().balanceFrom(accounts.debitsMinusCredits(id));
        if (balance != 0) {
            throw new AccountNotEmptyException(id, balance);
        }
        accounts.markClosed(id);
    }

    /**
     * Permanently deletes an account that has never been used. An account with entries is refused: its entries are
     * append-only history (the database forbids deleting them), and removing the account would erase who they
     * belong to. Such accounts can be closed instead.
     *
     * <p>Takes the same exclusive lock as closing, so a transfer into the account cannot slip in between the check
     * and the delete: an in-flight transfer finishes first (and the delete is then refused), and a later one finds
     * no account (404).
     */
    @Transactional
    public void deletePermanently(UUID id) {
        accounts.findByIdForClose(id).orElseThrow(() -> new AccountNotFoundException(id));
        long entries = accounts.entryCount(id);
        if (entries > 0) {
            throw new AccountHasHistoryException(id, entries);
        }
        accounts.delete(id);
    }
}
