package dev.thiru.bms.kernel.account;

import dev.thiru.bms.kernel.DomainException;

/**
 * Which cinemas a caller may manage (SPEC 3, roles and cinema ownership). An administrator has
 * every cinema in scope; a theatre owner only the cinemas linked to them. The links are not part of
 * the rule: the caller looks them up in its own transaction on every request, so an unlink takes
 * effect at once whatever the JWT says.
 *
 * @param ownerId the theatre owner's user id, or null for every cinema
 */
public record CinemaScope(Long ownerId) {

    private static final CinemaScope EVERYTHING = new CinemaScope(null);

    /** Whether {@code userId} is linked to {@code cinemaId}; the I/O stays with the caller. */
    @FunctionalInterface
    public interface Links {
        boolean linked(long userId, long cinemaId);
    }

    public static CinemaScope everything() {
        return EVERYTHING;
    }

    public static CinemaScope ownedBy(long userId) {
        return new CinemaScope(userId);
    }

    /** Every cinema for an administrator, else the cinemas linked to {@code userId}. */
    public static CinemaScope forCaller(long userId, boolean admin) {
        return admin ? EVERYTHING : ownedBy(userId);
    }

    public boolean isEverything() {
        return ownerId == null;
    }

    /**
     * Throws NOT_FOUND naming {@code what} when the cinema is outside the scope, so a stranger
     * cannot tell another owner's ids from ids that do not exist.
     */
    public void require(Links links, long cinemaId, String what) {
        if (ownerId != null && !links.linked(ownerId, cinemaId)) {
            throw DomainException.notFound(what);
        }
    }
}
