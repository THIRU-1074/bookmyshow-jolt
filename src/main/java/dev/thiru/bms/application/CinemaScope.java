package dev.thiru.bms.application;

import dev.thiru.bms.domain.port.Repositories;
import dev.thiru.bms.kernel.DomainException;

/**
 * Which cinemas a caller may manage (SPEC 3, roles and cinema ownership). An administrator has
 * every cinema in scope; a theatre owner only the cinemas linked to them. The links are read inside
 * the caller's transaction, so an unlink takes effect on the next request whatever the JWT says.
 *
 * @param ownerId the theatre owner's user id, or null for every cinema
 */
public record CinemaScope(Long ownerId) {

    private static final CinemaScope EVERYTHING = new CinemaScope(null);

    public static CinemaScope everything() {
        return EVERYTHING;
    }

    public static CinemaScope ownedBy(long userId) {
        return new CinemaScope(userId);
    }

    /**
     * Throws NOT_FOUND naming {@code what} when the cinema is outside the scope, so a stranger
     * cannot tell another owner's ids from ids that do not exist.
     */
    void require(Repositories r, long cinemaId, String what) {
        if (ownerId != null && !r.cinemaOwners().owns(ownerId, cinemaId)) {
            throw DomainException.notFound(what);
        }
    }
}
