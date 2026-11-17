package dev.thiru.bms.features.accounts;

import dev.thiru.bms.kernel.account.User;

/**
 * Maps the slice's domain values to its wire types, which are the records generated from {@code
 * api/openapi.yaml} ({@code dev.thiru.bms.api.generated}).
 */
final class AccountsDtos {

    private AccountsDtos() {}

    static dev.thiru.bms.api.generated.User user(User u) {
        return new dev.thiru.bms.api.generated.User(
                u.id(), u.email(), u.name(), u.roles().stream().map(Enum::name).sorted().toList());
    }
}
