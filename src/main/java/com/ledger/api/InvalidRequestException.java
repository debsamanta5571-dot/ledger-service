package com.ledger.api;

/** A request that is syntactically valid JSON but violates an API rule (bad header, bad query parameter). */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
