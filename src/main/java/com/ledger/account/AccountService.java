package com.ledger.account;

import com.ledger.account.dto.AccountResponse;
import com.ledger.account.dto.CreateAccountRequest;
import java.time.Instant;
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
                UUID.randomUUID(), req.name().strip(), req.currency(), req.type(), overdraft, Instant.now()));
        return AccountResponse.of(saved, 0L);
    }

    @Transactional(readOnly = true)
    public java.util.List<AccountResponse> list(int limit) {
        return accounts.findRecentWithNet(limit).stream()
                .map(a -> AccountResponse.of(a.account(), a.account().type().balanceFrom(a.debitsMinusCredits())))
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(UUID id) {
        Account account = accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
        long balance = account.type().balanceFrom(accounts.debitsMinusCredits(id));
        return AccountResponse.of(account, balance);
    }
}
