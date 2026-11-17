package dev.thiru.bms.web;

import dev.thiru.bms.contract.RefreshTokenStoreContract;
import dev.thiru.bms.ports.RefreshTokenStore;

/** The web tests' in-memory refresh-token store, held to the contract SQLite meets. */
class MemoryRefreshTokensContractTest extends RefreshTokenStoreContract {

    @Override
    protected RefreshTokenStore newStore() {
        return new WebKit.MemoryRefreshTokens();
    }
}
