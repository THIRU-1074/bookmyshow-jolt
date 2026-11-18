package dev.thiru.bms.ports;

import in.jolt.db.Sql;

public interface Db {
    <T> T inTransaction(java.util.function.Function<Sql, T> work);
}
