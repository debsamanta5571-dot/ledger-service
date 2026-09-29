package com.ledger.account;

import com.ledger.account.dto.AccountResponse;
import com.ledger.api.InvalidRequestException;
import org.springframework.web.bind.annotation.RequestParam;
import com.ledger.account.dto.CreateAccountRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/accounts/" + created.id())).body(created);
    }

    @GetMapping
    public java.util.List<AccountResponse> list(@RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 100) {
            throw new InvalidRequestException("'limit' must be between 1 and 100");
        }
        return service.list(limit);
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return service.get(id);
    }
}
