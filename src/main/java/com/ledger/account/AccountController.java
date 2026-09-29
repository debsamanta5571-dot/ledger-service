package com.ledger.account;

import com.ledger.account.dto.AccountResponse;
import com.ledger.api.InvalidRequestException;
import org.springframework.web.bind.annotation.RequestParam;
import com.ledger.account.dto.CreateAccountRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    public java.util.List<AccountResponse> list(@RequestParam(defaultValue = "50") int limit,
                                                @RequestParam(defaultValue = "false") boolean includeClosed) {
        if (limit < 1 || limit > 100) {
            throw new InvalidRequestException("'limit' must be between 1 and 100");
        }
        return service.list(limit, includeClosed);
    }

    /**
     * Without {@code permanent}: closes the account, which keeps its history. 204 on success and when already
     * closed; 409 if the balance is not zero.
     *
     * <p>With {@code permanent=true}: deletes the account for good, which is only allowed if it has never had a
     * transaction (409 otherwise, since its entries are append-only history). 404 if it does not exist.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean permanent) {
        if (permanent) {
            service.deletePermanently(id);
        } else {
            service.close(id);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return service.get(id);
    }
}
