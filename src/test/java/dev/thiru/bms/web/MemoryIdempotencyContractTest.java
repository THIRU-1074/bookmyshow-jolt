package dev.thiru.bms.web;

import dev.thiru.bms.contract.IdempotencyStoreContract;
import dev.thiru.bms.domain.port.IdempotencyStore;

/** The web tests' in-memory Idempotency-Key store, held to the contract SQLite meets. */
class MemoryIdempotencyContractTest extends IdempotencyStoreContract {

    @Override
    protected IdempotencyStore newStore() {
        return new WebKit.MemoryIdempotency();
    }
}
