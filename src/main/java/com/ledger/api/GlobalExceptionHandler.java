package com.ledger.api;

import com.ledger.account.AccountClosedException;
import com.ledger.account.AccountHasHistoryException;
import com.ledger.account.AccountNotEmptyException;
import com.ledger.account.AccountNotFoundException;
import com.ledger.ledger.InsufficientFundsException;
import com.ledger.ledger.UnbalancedTransactionException;
import com.ledger.transfer.IdempotencyConflictException;
import com.ledger.transfer.InvalidTransferException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every failure to RFC 7807 problem+json. Framework exceptions (malformed JSON, missing header, bad
 * path variable, ...) are handled by the base class, which already emits problem details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail accountNotFound(AccountNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "account-not-found", "Account not found", e.getMessage());
    }

    @ExceptionHandler(AccountNotEmptyException.class)
    ProblemDetail accountNotEmpty(AccountNotEmptyException e) {
        ProblemDetail p = problem(HttpStatus.CONFLICT, "account-not-empty", "Account not empty", e.getMessage());
        p.setProperty("balance", e.balance());
        return p;
    }

    @ExceptionHandler(AccountHasHistoryException.class)
    ProblemDetail accountHasHistory(AccountHasHistoryException e) {
        ProblemDetail p = problem(HttpStatus.CONFLICT, "account-has-history", "Account has history", e.getMessage());
        p.setProperty("entryCount", e.entryCount());
        return p;
    }

    @ExceptionHandler(AccountClosedException.class)
    ProblemDetail accountClosed(AccountClosedException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "account-closed", "Account closed", e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds(InsufficientFundsException e) {
        ProblemDetail p = problem(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-funds", "Insufficient funds",
                e.getMessage());
        p.setProperty("available", e.available());
        return p;
    }

    @ExceptionHandler(InvalidTransferException.class)
    ProblemDetail invalidTransfer(InvalidTransferException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-transfer", "Invalid transfer", e.getMessage());
    }

    @ExceptionHandler(UnbalancedTransactionException.class)
    ProblemDetail unbalanced(UnbalancedTransactionException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "unbalanced-transaction", "Unbalanced transaction",
                e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException e) {
        return problem(HttpStatus.CONFLICT, "idempotency-key-reuse", "Idempotency key reused", e.getMessage());
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    ProblemDetail forbiddenOperation(ForbiddenOperationException e) {
        return problem(HttpStatus.FORBIDDEN, "forbidden-operation", "Not allowed", e.getMessage());
    }

    @ExceptionHandler(ApiKeyController.NotFoundException.class)
    ProblemDetail apiKeyNotFound(ApiKeyController.NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "api-key-not-found", "API key not found", e.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalidRequest(InvalidRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", e.getMessage());
    }

    /**
     * Lock timeouts, deadlocks, and "no database connection" are temporary. The whole transfer rolled back
     * (including its idempotency claim), so the client can safely retry with the same Idempotency-Key.
     */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> dataAccess(DataAccessException e) {
        if (!isTemporary(e)) {
            return ResponseEntity.internalServerError().body(unexpected(e));
        }
        log.warn("Temporary database failure: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "2")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable", "Service unavailable",
                        "The ledger is busy or its database is unreachable; retry the same request shortly"));
    }

    /**
     * Spring does not categorise every Postgres "try again" error: a lock timeout (SQL state 55P03) arrives as a
     * plain {@link UncategorizedSQLException}, so the SQL state is checked too.
     */
    static boolean isTemporary(DataAccessException e) {
        if (e instanceof TransientDataAccessException || e instanceof DataAccessResourceFailureException) {
            return true;
        }
        return e instanceof UncategorizedSQLException u && u.getSQLException() != null
                && TEMPORARY_SQL_STATES.contains(u.getSQLException().getSQLState());
    }

    /** lock_not_available, query_canceled (statement/lock timeout), serialization_failure, deadlock_detected. */
    private static final Set<String> TEMPORARY_SQL_STATES = Set.of("55P03", "57014", "40001", "40P01");

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "An unexpected error occurred");
    }

    /** Adds a per-field {@code errors} list to Bean Validation failures. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ex.getBody();
        body.setType(URI.create("urn:ledger:problem:validation-failed"));
        body.setTitle("Validation failed");
        body.setDetail("One or more fields are invalid");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:ledger:problem:" + slug));
        p.setTitle(title);
        return p;
    }
}
