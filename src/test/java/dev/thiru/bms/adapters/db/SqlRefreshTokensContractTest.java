package dev.thiru.bms.adapters.db;

import dev.thiru.bms.contract.RefreshTokenStoreContract;
import dev.thiru.bms.ports.RefreshTokenStore;
import in.jolt.db.Db;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

/** The production refresh-token table, held to the refresh-token contract. */
class SqlRefreshTokensContractTest extends RefreshTokenStoreContract {

    @TempDir Path dir;
    private Db db;

    @Override
    protected RefreshTokenStore newStore() {
        String url = Sqlite.jdbcUrl(dir.resolve("contract.db"));
        SchemaRunner.migrate(url, java.time.Clock.systemUTC());
        db = Sqlite.open(url, 4, Duration.ofSeconds(10));
        return new SqlRefreshTokens(db);
    }

    @AfterEach
    void closeDb() {
        db.close();
    }
}
