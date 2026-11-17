package dev.thiru.bms.web;

import dev.thiru.bms.contract.IdempotencyStoreContract;
import in.jolt.IdempotencyStore;

/** Jolt's in-memory store, which the web tests use, held to the contract SQLite meets. */
class JoltIdempotencyContractTest extends IdempotencyStoreContract {

    @Override
    protected IdempotencyStore newStore() {
        return IdempotencyStore.inMemory();
    }
}
