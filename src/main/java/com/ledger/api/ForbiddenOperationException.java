package com.ledger.api;

/** The caller is authenticated and has the endpoint's scope, but this particular operation is not theirs to do. */
public class ForbiddenOperationException extends RuntimeException {
    public ForbiddenOperationException(String message) {
        super(message);
    }
}
