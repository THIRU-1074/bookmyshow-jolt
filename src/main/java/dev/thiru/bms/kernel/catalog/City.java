package dev.thiru.bms.domain;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.regex.Pattern;

/** A city, identified by a slug, whose zone turns epoch instants into local show times. */
public record City(String id, String name, ZoneId timeZone) {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9-]{2,40}");

    public City {
        if (id == null || !SLUG.matcher(id).matches()) {
            throw DomainException.validation("id", "must match [a-z0-9-]{2,40}");
        }
        if (name == null || name.isBlank()) {
            throw DomainException.validation("name", "must not be blank");
        }
        if (timeZone == null) {
            throw DomainException.validation("timeZone", "is required");
        }
    }

    public static City of(String id, String name, String timeZone) {
        try {
            return new City(id, name, ZoneId.of(timeZone));
        } catch (DateTimeException | NullPointerException e) {
            throw DomainException.validation("timeZone", "must be a valid IANA zone");
        }
    }
}
