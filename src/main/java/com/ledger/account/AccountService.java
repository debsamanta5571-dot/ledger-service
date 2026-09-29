package com.ledger.account;

import com.ledger.account.dto.AccountResponse;
import com.ledger.account.dto.CreateAccountRequest;
import com.ledger.api.Caller;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two tiers of caller. A normal caller only ever acts on accounts they own; someone else's account is reported
 * exactly like a missing one ({@link AccountNotFoundException}, 404). An admin ({@code ledger:admin}) may act on every
 * account; when they act on one that is not theirs, it is logged, and money movements record them as the initiator.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public AccountResponse create(Caller caller, CreateAccountRequest req) {
        long overdraft = req.overdraftLimit() == null ? 0L : req.overdraftLimit();
        Account saved = accounts.insert(new Account(UUID.randomUUID(), caller.id(), caller.name(), req.name().strip(),
                req.currency(), req.type(), overdraft, Instant.now(), null));
        return AccountResponse.of(saved, 0L, 0L);
    }

    /** A normal caller's own accounts; an admin sees everyone's unless {@code mineOnly}. */
    @Transactional(readOnly = true)
    public List<AccountResponse> list(Caller caller, int limit, boolean includeClosed, boolean mineOnly) {
        return accounts.findRecentVisibleWithNet(caller, limit, includeClosed, mineOnly).stream()
                .map(a -> AccountResponse.of(a.account(), a.account().type().balanceFrom(a.debitsMinusCredits()),
                        a.entryCount()))
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(Caller caller, UUID id) {
        Account account = accounts.findVisible(id, caller).orElseThrow(() -> new AccountNotFoundException(id));
        long balance = account.type().balanceFrom(accounts.debitsMinusCredits(id));
        return AccountResponse.of(account, balance, accounts.entryCount(id));
    }

    /**
     * "Removes" an account by closing it; nothing is deleted (see V5__account_closing.sql). Only a zero balance can
     * be closed, so no money is stranded. Closing an already-closed account is a no-op, so DELETE stays idempotent.
     */
    @Transactional
    public void close(Caller caller, UUID id) {
        Account account = accounts.findVisibleForClose(id, caller).orElseThrow(() -> new AccountNotFoundException(id));
        if (account.isClosed()) {
            return;
        }
        // Read under the exclusive lock: no transfer can add or remove an entry for this account until we commit.
        long balance = account.type().balanceFrom(accounts.debitsMinusCredits(id));
        if (balance != 0) {
            throw new AccountNotEmptyException(id, balance);
        }
        accounts.markClosed(id);
        logAdminAction("closed", caller, account);
    }

    /**
     * Permanently deletes an account that has never been used. An account with entries is refused: its entries are
     * append-only history (the database forbids deleting them), and removing the account would erase who they
     * belong to. Such accounts can be closed instead. This holds for admins too: nobody can erase history.
     *
     * <p>Takes the same exclusive lock as closing, so a transfer into the account cannot slip in between the check
     * and the delete: an in-flight transfer finishes first (and the delete is then refused), and a later one finds
     * no account (404).
     */
    @Transactional
    public void deletePermanently(Caller caller, UUID id) {
        Account account = accounts.findVisibleForClose(id, caller).orElseThrow(() -> new AccountNotFoundException(id));
        long entries = accounts.entryCount(id);
        if (entries > 0) {
            throw new AccountHasHistoryException(id, entries);
        }
        accounts.delete(id);
        logAdminAction("deleted", caller, account);
    }

    private static void logAdminAction(String action, Caller caller, Account account) {
        if (!caller.id().equals(account.ownerId())) {
            log.info("Admin {} ({}) {} account {} owned by {} ({})", caller.id(), caller.name(), action, account.id(),
                    account.ownerId(), account.ownerName());
        }
    }
}
