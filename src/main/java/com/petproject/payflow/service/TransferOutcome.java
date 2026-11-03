package com.petproject.payflow.service;

import com.petproject.payflow.domain.Transfer;

public record TransferOutcome(Transfer transfer, boolean replayed) {

    public static TransferOutcome created(Transfer transfer) {
        return new TransferOutcome(transfer, false);
    }

    public static TransferOutcome replay(Transfer transfer) {
        return new TransferOutcome(transfer, true);
    }
}
