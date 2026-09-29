package com.ledger.transfer;

import com.ledger.transfer.dto.TransferResponse;

public record TransferResult(int status, TransferResponse body, boolean replayed) {
}
