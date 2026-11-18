package dev.thiru.bms.kernel.account;

/**
 * The three roles of SPEC 3. Every account holds {@link #AUDIENCE}; {@link #THEATRE_OWNER} and
 * {@link #ADMIN} are added on top of it and only by an admin (or, for the first admin, startup).
 */
public enum Role {
    /** Browses and books; acts only on its own holds and bookings. */
    AUDIENCE,
    /** Manages screens, shows and reports of the cinemas linked to it. */
    THEATRE_OWNER,
    /** Manages everything. */
    ADMIN
}
