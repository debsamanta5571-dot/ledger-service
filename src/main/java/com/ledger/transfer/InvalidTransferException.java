package com.ledger.transfer;

/** The request is well-formed but the transfer is not allowed (same account, currency or type mismatch). */
public class InvalidTransferException extends RuntimeException {
    public InvalidTransferException(String message) {
        super(message);
    }
}
