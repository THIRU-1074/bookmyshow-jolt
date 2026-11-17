package dev.thiru.bms.domain;

/** A cinema in a city. Name uniqueness within the city is a repository concern. */
public record Cinema(long id, String cityId, String name, String address) {

    public Cinema {
        if (cityId == null || cityId.isBlank()) {
            throw DomainException.validation("cityId", "is required");
        }
        if (name == null || name.isBlank()) {
            throw DomainException.validation("name", "must not be blank");
        }
        if (address == null) {
            throw DomainException.validation("address", "is required");
        }
    }
}
