package com.ledger.statement;

import com.ledger.api.Caller;
import com.ledger.statement.dto.StatementResponse;
import org.springframework.security.core.Authentication;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatementController {

    private final StatementService service;

    public StatementController(StatementService service) {
        this.service = service;
    }

    @Operation(summary = "Account statement",
            description = "Entries within an inclusive UTC date range with the running balance after each one, "
                    + "paginated (page is zero-based, size 1-100).")
    @GetMapping("/accounts/{id}/statement")
    public StatementResponse statement(
            Authentication caller,
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.statement(Caller.of(caller), id, from, to, page, size);
    }
}
