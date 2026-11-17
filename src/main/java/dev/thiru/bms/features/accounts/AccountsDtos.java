package dev.thiru.bms.features.accounts;

import dev.thiru.bms.kernel.account.User;
import java.util.List;

/**
 * The slice's wire types, copied from {@code web.Dtos} and {@code web.AuthModule}: same component
 * names, order and types, so the JSON is unchanged ({@code RecordGeneratorTest} checks them against
 * {@code api/openapi.yaml}).
 */
final class AccountsDtos {

    private AccountsDtos() {}

    record RegisterRequest(String email, String password, String name) {}

    record LoginRequest(String email, String password) {}

    record UserDto(long id, String email, String name, List<String> roles) {
        static UserDto of(User u) {
            return new UserDto(
                    u.id(),
                    u.email(),
                    u.name(),
                    u.roles().stream().map(Enum::name).sorted().toList());
        }
    }

    record AuthResponse(UserDto user, String accessToken, long expiresIn) {}

    record TokenResponse(String accessToken, long expiresIn) {}
}
