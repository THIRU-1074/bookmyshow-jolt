package dev.thiru.bms.adapters.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.thiru.bms.domain.DomainException;
import dev.thiru.bms.domain.Show;
import org.junit.jupiter.api.Test;

class SqliteUnitOfWorkTest extends DbTest {

    @Test
    void commitsWhenTheWorkReturns() {
        long id = anyShowId();
        tx(r -> r.shows().save(r.shows().findById(id).orElseThrow().cancel()));
        assertEquals(
                "CANCELLED",
                db.queryOne("SELECT status FROM show WHERE id = ?", r -> r.getString(1), id)
                        .orElseThrow());
    }

    @Test
    void returnsTheWorkResult() {
        assertEquals(3, (int) tx(r -> r.cities().findAll().size()));
    }

    @Test
    void rollsBackAndRethrowsTheSameDomainExceptionOnFailure() {
        long id = anyShowId();
        DomainException thrown =
                assertThrows(
                        DomainException.class,
                        () ->
                                tx(
                                        r -> {
                                            Show cancelled =
                                                    r.shows()
                                                            .save(
                                                                    r.shows()
                                                                            .findById(id)
                                                                            .orElseThrow()
                                                                            .cancel());
                                            throw DomainException.notFound("x" + cancelled.id());
                                        }));
        assertEquals(DomainException.Code.NOT_FOUND, thrown.code());
        assertEquals(
                "SCHEDULED",
                db.queryOne("SELECT status FROM show WHERE id = ?", r -> r.getString(1), id)
                        .orElseThrow());
    }

    @Test
    void rollsBackOnAnyRuntimeException() {
        long id = anyShowId();
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx(
                                r -> {
                                    r.shows().save(r.shows().findById(id).orElseThrow().cancel());
                                    throw new IllegalStateException("boom");
                                }));
        assertEquals(
                "SCHEDULED",
                db.queryOne("SELECT status FROM show WHERE id = ?", r -> r.getString(1), id)
                        .orElseThrow());
    }
}
