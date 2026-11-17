package dev.thiru.bms.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestDbTest {

    @Test
    void opensAMigratedSeededDatabaseBehindPortsDb(@TempDir Path dir) {
        try (TestDb test = TestDb.open(dir)) {
            List<String> cities =
                    test.db()
                            .inTransaction(
                                    tx ->
                                            tx.query(
                                                    "SELECT id FROM city ORDER BY id",
                                                    rs -> rs.getString("id")));
            assertEquals(List.of("bengaluru", "chennai", "mumbai"), cities);
        }
    }
}
