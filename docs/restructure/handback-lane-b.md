# Wave 2, lane B hand-back: `features.accounts`

Branch `feat/slice-accounts`, from `integration` at `229abe5`. New files only:

- `src/main/java/dev/thiru/bms/features/accounts/`: `AuthRoutes`, `Accounts`, `AccountsSql`, `AccountsDtos`, `JoltRefreshStore`
- `src/test/java/dev/thiru/bms/features/accounts/`: `AuthRoutesTest` (SliceKit), `AccountsTest` and `AccountsSqlTest` (TestDb)

No hot spot is edited. The slice imports only `kernel`, `ports`, `web.ApiException`, `web.Principal`, Jolt and the JDK.

## 1. Swap lines

`web/WebModules.java`, in `routes`: delete the `Accounts accounts = ...` and `RefreshTokens tokens = ...` statements (including the comment on `.claims`), and replace

```java
        pipeline.module(new AuthModule(accounts, tokens));
```

with

```java
        pipeline.module(
                AuthRoutes.of(
                        pipeline.jwt(),
                        new dev.thiru.bms.features.accounts.Accounts(
                                deps.db(), deps.hasher(), deps.clock()),
                        deps.refreshTokens()));
```

Then delete the private `claimsFor` method. In the imports, add `dev.thiru.bms.features.accounts.AuthRoutes` and drop `application.Accounts`, `kernel.account.Role`, `kernel.account.User`, `in.jolt.HttpException` and `in.jolt.auth.RefreshTokens`, which nothing else in `WebModules` uses. Once `application.Accounts` is deleted, the fully qualified name can become a plain import of `dev.thiru.bms.features.accounts.Accounts`.

`Main.java`: drop `import dev.thiru.bms.application.Accounts;` and replace

```java
                        new Accounts(uow, hasher, clock)
```

with

```java
                        new dev.thiru.bms.features.accounts.Accounts(sliceDb, hasher, clock)
```

(or import the slice's `Accounts` and keep `new Accounts(sliceDb, hasher, clock)`).

## 2. Deletions

- Main: `web.AuthModule`, `web.JoltRefreshStore`, `application.Accounts`.
- `web.Dtos`: the records `UserDto`, `AuthResponse` and `TokenResponse` and the `// ---- auth ----` header. Keep `import kernel.account.User`, because `AccountDto` still uses it.
- Tests: `application.AccountsTest`, ported in full to `features.accounts.AccountsTest`.
- `web.AuthApiTest`: 12 of its 13 cases are in `AuthRoutesTest`, so delete them. **Move** `aTokenIssuedBeforeTheRoleRenameStillCountsAsAudience` to `web.RbacTest`, or another `web` test, before deleting the file. That case tests `web.Principal` and the gate on `/api/bookings` and `/api/admin/stats`, not the auth routes, so it does not belong in the slice.
- Not deleted now: `SqlBookings.Users` (with `USER_COLS` and `USER`) still serves the legacy repositories until wave 6. `AccountsSql` duplicates its SQL, as PLAN allows.

## 3. `WEB_RECORDS` (`RecordGeneratorTest`), all *replace*

| Schema | Record |
|---|---|
| `User` | `dev.thiru.bms.features.accounts.AccountsDtos$UserDto` |
| `AuthResponse` | `dev.thiru.bms.features.accounts.AccountsDtos$AuthResponse` |
| `TokenResponse` | `dev.thiru.bms.features.accounts.AccountsDtos$TokenResponse` |
| `RegisterRequest` | `dev.thiru.bms.features.accounts.AccountsDtos$RegisterRequest` |
| `LoginRequest` | `dev.thiru.bms.features.accounts.AccountsDtos$LoginRequest` |

Remove `User`, `AuthResponse` and `TokenResponse` from the `dtos` array, and replace the two `AuthModule$...` lines with:

```java
        String accounts = "dev.thiru.bms.features.accounts.AccountsDtos$";
        add(m, "User", accounts + "UserDto");
        add(m, "AuthResponse", accounts + "AuthResponse");
        add(m, "TokenResponse", accounts + "TokenResponse");
        add(m, "RegisterRequest", accounts + "RegisterRequest");
        add(m, "LoginRequest", accounts + "LoginRequest");
```

## 4. Swap proof

The swap lines above (both `WebModules` and `Main`), plus the `WEB_RECORDS` change, were applied in the working tree and the full `mvn -B verify` was run:

- **BUILD SUCCESS, 558 tests, 0 failures, 0 errors, 0 skipped.** Spotless and JaCoCo passed.
- Among them: `ApiSchemaTest` (no schema edit), `AuthApiTest` (13, now against `AuthRoutes` through `WebKit`), `AdminUsersApiTest` (4), `RbacTest` (12), `RbacMatrixTest` (3), `RecordGeneratorTest` (4, the slice records checked against the schema), `EndToEndTest` (both journeys), `MainTest` (12, admin bootstrap through the slice's `ensureAdmin`), `ArchitectureTest`, `TargetArchitectureTest` and every contract suite.

The change was then reverted. With it reverted, the branch is also green with 558 tests: 523 at step 0, plus the 35 slice tests.

## 5. Lines of code

| | Main | Tests |
|---|---|---|
| New slice | 432 (`AuthRoutes` 153, `Accounts` 134, `AccountsSql` 64, `JoltRefreshStore` 49, `AccountsDtos` 32) | 650 (`AuthRoutesTest` 382, `AccountsTest` 166, `AccountsSqlTest` 102) |
| Legacy deleted at step 3 | about 335 (`AuthModule` 120, `application.Accounts` 129, `JoltRefreshStore` 49, the three `Dtos` records 15, the wiring and `claimsFor` in `WebModules` about 22) | 316 (`AccountsTest` 94, `AuthApiTest` 222) |

The slice is about 100 lines larger in main code. Most of the difference is `AccountsSql` (64), which `SqlBookings.Users` duplicates until wave 6. The tests grew because `AuthRoutesTest` adds what the legacy tests never checked:

- exact response bodies
- exact cookie attributes
- role re-read on refresh
- a deleted account on refresh and on `/api/me`
- the claims function's bad subjects
- the register rate limit
- logout without a cookie

`AccountsTest` adds the decoy-hash and null-input cases, and `AccountsSqlTest` adds the racing-duplicate case.

## Port behaviour relied on that no contract covers

- `Db.Tx.insert` throws `Db.ConstraintViolation` on the `app_user.email` UNIQUE clash. `DbContract` covers UNIQUE in general, and `AccountsSqlTest` covers this table.
- None beyond that: `RefreshTokenStoreContract` covers the store bridge, and `PasswordHasherContract` covers the hasher.

## Notes for review

- **Behaviour is unchanged on purpose.** The decoy hash is still evaluated eagerly in `login` (`orElse(decoy())`): it is made once, then cached. The slice's `AuthRoutes` still answers `/api/me` for a deleted account with 401 `Unknown user`.
- **`Secure` depends on `req.scheme()`**, as before. Behind Render's proxy it is `https` only when `TRUST_PROXY` covers the proxy. This is existing configuration, not slice code. `AuthRoutesTest.theCookieIsSecureOnlyOverHttps` covers the attribute string, because `SliceKit` has no TLS or trusted proxy.
- **`ensureAdmin` now runs on `sliceDb`.** That is the same pool as `uow`, with one `BEGIN IMMEDIATE` per call, as before.
