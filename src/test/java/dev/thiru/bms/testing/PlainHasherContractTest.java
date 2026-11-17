package dev.thiru.bms.testing;

import dev.thiru.bms.contract.PasswordHasherContract;
import dev.thiru.bms.ports.PasswordHasher;

/** The slice and web tests' plain-text hasher, held to the contract PBKDF2 meets. */
class PlainHasherContractTest extends PasswordHasherContract {

    @Override
    protected PasswordHasher newHasher() {
        return new PlainHasher();
    }
}
