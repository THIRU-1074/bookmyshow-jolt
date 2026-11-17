package dev.thiru.bms.web;

import dev.thiru.bms.application.Accounts;
import dev.thiru.bms.kernel.account.User;
import in.jolt.HttpException;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.RouteModule;
import in.jolt.Router;
import in.jolt.auth.RefreshTokens;
import java.time.Duration;

/**
 * Register, login, refresh, logout and me. The refresh token lives only in an HttpOnly cookie
 * scoped to {@code /api/auth}; the access token is returned in the body for in-memory use.
 */
final class AuthModule implements RouteModule {

    static final String COOKIE = "bms_refresh";
    static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    static final Duration REFRESH_TTL = Duration.ofDays(30);

    record RegisterRequest(String email, String password, String name) {}

    record LoginRequest(String email, String password) {}

    private final Accounts accounts;
    private final RefreshTokens tokens;

    AuthModule(Accounts accounts, RefreshTokens tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    @Override
    public void register(Router r) {
        r.post(
                "/api/auth/register",
                (req, res) -> {
                    RegisterRequest in = req.body(RegisterRequest.class);
                    // Roles are never read from the body: registration only ever creates a USER.
                    User user = accounts.register(in.email(), in.password(), in.name());
                    signIn(req, res, user, 201);
                });
        r.post(
                "/api/auth/login",
                (req, res) -> {
                    LoginRequest in = req.body(LoginRequest.class);
                    signIn(req, res, accounts.login(in.email(), in.password()), 200);
                });
        r.post(
                "/api/auth/refresh",
                (req, res) -> {
                    String token = req.cookie(COOKIE);
                    if (token == null || token.isEmpty()) {
                        throw ApiException.unauthenticated("Missing refresh token");
                    }
                    RefreshTokens.Pair pair;
                    try {
                        pair = tokens.refresh(token);
                    } catch (HttpException e) {
                        clearCookie(req, res);
                        throw e;
                    }
                    setCookie(req, res, pair.refreshToken(), REFRESH_TTL);
                    res.noStore()
                            .json(
                                    new Dtos.TokenResponse(
                                            pair.accessToken(), ACCESS_TTL.toSeconds()));
                });
        r.post(
                "/api/auth/logout",
                (req, res) -> {
                    String token = req.cookie(COOKIE);
                    if (token != null && !token.isEmpty()) {
                        try {
                            tokens.revoke(token);
                        } catch (HttpException ignored) {
                            // An unknown or expired token is already unusable.
                        }
                    }
                    clearCookie(req, res);
                    res.status(204);
                });
        r.get(
                "/api/me",
                (req, res) -> {
                    User u =
                            accounts.find(Principal.of(req).userId())
                                    .orElseThrow(
                                            () -> ApiException.unauthenticated("Unknown user"));
                    res.noStore().json(Dtos.UserDto.of(u));
                });
    }

    private void signIn(Request req, Response res, User user, int status) {
        // Roles come from the stored user at issue time, never from the request.
        RefreshTokens.Pair pair = tokens.issue(Long.toString(user.id()));
        setCookie(req, res, pair.refreshToken(), REFRESH_TTL);
        res.status(status)
                .noStore()
                .json(
                        new Dtos.AuthResponse(
                                Dtos.UserDto.of(user), pair.accessToken(), ACCESS_TTL.toSeconds()));
    }

    private static void setCookie(Request req, Response res, String value, Duration maxAge) {
        String secure = "https".equalsIgnoreCase(req.scheme()) ? "; Secure" : "";
        res.cookie(
                COOKIE,
                value,
                "Path=/api/auth; HttpOnly; SameSite=Strict; Max-Age="
                        + maxAge.toSeconds()
                        + secure);
    }

    private static void clearCookie(Request req, Response res) {
        setCookie(req, res, "", Duration.ZERO);
    }
}
