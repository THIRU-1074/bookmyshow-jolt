package dev.thiru.bms.domain;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

/** A film. Language and genre vocabularies are open; only shape is validated here. */
public record Movie(
        long id,
        String title,
        List<String> languages,
        List<String> genres,
        Certificate certificate,
        int durationMinutes,
        LocalDate releaseDate,
        String posterUrl,
        String description) {

    public Movie {
        if (title == null || title.isBlank() || title.length() > 120) {
            throw DomainException.validation("title", "must be 1 to 120 characters");
        }
        languages = distinctNonEmpty("languages", languages);
        genres = distinctNonEmpty("genres", genres);
        if (certificate == null) {
            throw DomainException.validation("certificate", "must be one of U, UA, A, S");
        }
        if (durationMinutes < 1 || durationMinutes > 400) {
            throw DomainException.validation("durationMinutes", "must be 1 to 400");
        }
        if (releaseDate == null) {
            throw DomainException.validation("releaseDate", "is required");
        }
        // "//host" is protocol-relative and would let a poster leave https, so it is not
        // site-relative.
        boolean ok =
                posterUrl != null
                        && (posterUrl.startsWith("https://")
                                || (posterUrl.startsWith("/") && !posterUrl.startsWith("//")));
        if (!ok) {
            throw DomainException.validation("posterUrl", "must be https or a site-relative path");
        }
        if (description == null || description.length() > 2000) {
            throw DomainException.validation("description", "must be at most 2000 characters");
        }
    }

    private static List<String> distinctNonEmpty(String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            throw DomainException.validation(field, "must not be empty");
        }
        if (new HashSet<>(values).size() != values.size()
                || values.stream().anyMatch(v -> v == null || v.isBlank())) {
            throw DomainException.validation(field, "must not contain blanks or duplicates");
        }
        return List.copyOf(values);
    }
}
