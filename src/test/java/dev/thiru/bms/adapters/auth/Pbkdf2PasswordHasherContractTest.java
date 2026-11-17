package dev.thiru.bms.adapters.auth;

import dev.thiru.bms.contract.PasswordHasherContract;
import dev.thiru.bms.domain.port.PasswordHasher;

/** The production PBKDF2 hasher, held to the hasher contract. */
class Pbkdf2PasswordHasherContractTest extends PasswordHasherContract {

    @Override
    protected PasswordHasher newHasher() {
        return new Pbkdf2PasswordHasher();
    }
}
