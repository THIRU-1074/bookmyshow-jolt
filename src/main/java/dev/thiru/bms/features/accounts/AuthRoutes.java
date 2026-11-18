package dev.thiru.bms.features.accounts;

import dev.thiru.bms.api.generated.AuthResponse;
import dev.thiru.bms.api.generated.LoginRequest;
import dev.thiru.bms.api.generated.RegisterRequest;
import dev.thiru.bms.api.generated.TokenResponse;
import dev.thiru.bms.kernel.account.Role;
import dev.thiru.bms.kernel.account.User;
import dev.thiru.bms.ports.RefreshTokenStore;
import dev.thiru.bms.web.ApiException;
import dev.thiru.bms.web.Principal;
import in.jolt.HttpException;
import in.jolt.Request;
import in.jolt.Response;
import in.jolt.RouteModule;
import in.jolt.Router;
import in.jolt.auth.JwtAuth;
import in.jolt.auth.RefreshTokens;
import java.time.Duration;
import java.util.Map;

/**
 * Register, login, refresh, logout and me (SPEC 7). The refresh token lives only in an HttpOnly
 * cookie scoped to {@code /api/auth}; the access token is returned in the body for in-memory use.
 * The per-IP limits and CSRF on {@code /api/auth} are the pipeline's, not this module's.
 */
public final class AuthRoutes implements RouteModule {

    static final String COOKIE = "bms_refresh";
    static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    static final Duration REFRESH_TTL = Duration.ofDays(30);

    private final Accounts accounts;
    private final RefreshTokens tokens;

    private AuthRoutes(Accounts accounts, RefreshTokens tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    /**
     * The routes, with Jolt refresh tokens signed by {@code jwt} and persisted in {@code store}.
     */
    public static AuthRoutes of(JwtAuth jwt, Accounts accounts, RefreshTokenStore store) {
        RefreshTokens tokens =
                RefreshTokens.of(jwt)
                        .accessTtl(ACCESS_TTL)
                        .refreshTtl(REFRESH_TTL)
                        .store(new JoltRefreshStore(store))
                        // Re-read on every issue and refresh: a demotion or deletion is picked up
                        // the next time the access token is renewed.
                        .claims(subject -> claimsFor(accounts, subject));
        return new AuthRoutes(accounts, tokens);
    }

    /** The access token claims of {@code subject}: its stored roles, sorted. */
    static Map<String, ?> claimsFor(Accounts accounts, String subject) {
        long id;
        try {
            id = Long.parseLong(subject);
        } catch (NumberFormatException e) {
            throw new HttpException(401, "Invalid token");
        }
        User user = accounts.find(id).orElseThrow(() -> new HttpException(401, "Invalid token"));
        return Map.of("roles", user.roles().stream().map(Role::name).sorted().toList());
    }

    @Override
    public void register(Router r) {
        r.post(
                "/api/auth/register",
                (req, res) -> {
                    RegisterRequest in = req.body(RegisterRequest.class);
                    // Roles are never read from the body: registration only creates AUDIENCE.
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
                            .json(new TokenResponse(pair.accessToken(), ACCESS_TTL.toSeconds()));
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
                    res.noStore().json(AccountsDtos.user(u));
                });
    }

    private void signIn(Request req, Response res, User user, int status) {
        // Roles come from the stored user at issue time (the claims function), never the request.
        RefreshTokens.Pair pair = tokens.issue(Long.toString(user.id()));
        setCookie(req, res, pair.refreshToken(), REFRESH_TTL);
        res.status(status)
                .noStore()
                .json(
                        new AuthResponse(
                                AccountsDtos.user(user),
                                pair.accessToken(),
                                ACCESS_TTL.toSeconds()));
    }

    /** The refresh cookie's attributes; {@code Secure} only when the request came over HTTPS. */
    static String cookieAttributes(boolean https, Duration maxAge) {
        return "Path=/api/auth; HttpOnly; SameSite=Strict; Max-Age="
                + maxAge.toSeconds()
                + (https ? "; Secure" : "");
    }

    private static void setCookie(Request req, Response res, String value, Duration maxAge) {
        res.cookie(COOKIE, value, cookieAttributes("https".equalsIgnoreCase(req.scheme()), maxAge));
    }

    private static void clearCookie(Request req, Response res) {
        setCookie(req, res, "", Duration.ZERO);
    }
}
