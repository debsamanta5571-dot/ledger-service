package com.ledger.statement;

import com.ledger.account.Account;
import com.ledger.account.AccountNotFoundException;
import com.ledger.account.AccountRepository;
import com.ledger.api.InvalidRequestException;
import com.ledger.statement.dto.StatementResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatementService {

    public static final int MAX_PAGE_SIZE = 100;
    static final int MAX_PAGE = 1_000_000;
    static final LocalDate EARLIEST = LocalDate.of(1970, 1, 1);

    private final AccountRepository accounts;
    private final StatementRepository statements;

    public StatementService(AccountRepository accounts, StatementRepository statements) {
        this.accounts = accounts;
        this.statements = statements;
    }

    /** Both dates are inclusive, interpreted as UTC calendar days. Defaults: from = 1970-01-01, to = today. */
    @Transactional(readOnly = true)
    public StatementResponse statement(String owner, UUID accountId, LocalDate from, LocalDate to, int page,
                                       int size) {
        LocalDate effectiveFrom = from == null ? EARLIEST : from;
        LocalDate effectiveTo = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new InvalidRequestException("'from' must not be after 'to'");
        }
        if (page < 0 || page > MAX_PAGE) {
            throw new InvalidRequestException("'page' must be between 0 and " + MAX_PAGE);
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("'size' must be between 1 and " + MAX_PAGE_SIZE);
        }

        Account account = accounts.findOwned(accountId, owner).orElseThrow(() -> new AccountNotFoundException(accountId));
        OffsetDateTime start = effectiveFrom.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime endExclusive = effectiveTo.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        StatementRepository.Totals totals = statements.totals(accountId, start, endExclusive);
        int sign = (int) account.type().balanceFrom(1);
        long opening = account.type().balanceFrom(totals.openingNet());
        long closing = account.type().balanceFrom(totals.openingNet() + totals.inRangeNet());

        var lines = statements.page(accountId, start, endExclusive, sign, opening, size, (long) page * size);
        int totalPages = (int) ((totals.count() + size - 1) / size);
        return new StatementResponse(accountId, account.currency(), effectiveFrom, effectiveTo, opening, closing,
                page, size, totals.count(), totalPages, lines);
    }
}
