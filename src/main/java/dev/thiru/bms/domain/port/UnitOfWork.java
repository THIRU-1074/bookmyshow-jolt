package dev.thiru.bms.domain.port;

import java.util.function.Function;

/**
 * Transaction boundary. The adapter opens a {@code BEGIN IMMEDIATE} transaction, hands the work
 * repositories bound to it, commits on return and rolls back on any exception.
 */
public interface UnitOfWork {

    <T> T inTransaction(Function<Repositories, T> work);
}
