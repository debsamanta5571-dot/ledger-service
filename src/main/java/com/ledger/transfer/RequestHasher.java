package com.ledger.transfer;

import com.ledger.transfer.dto.TransferRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes the parsed request rather than the raw bytes, so whitespace or field order changes in the
 * JSON do not turn a legitimate retry into a 409.
 */
public final class RequestHasher {

    private RequestHasher() {
    }

    public static String hash(TransferRequest r) {
        String canonical = String.join("|",
                String.valueOf(r.fromAccountId()),
                String.valueOf(r.toAccountId()),
                String.valueOf(r.amount()),
                String.valueOf(r.currency()),
                r.description() == null ? "" : r.description());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
