package dev.thiru.bms.features.catalog;

import dev.thiru.bms.api.generated.MovieDetail;
import dev.thiru.bms.api.generated.MovieSummary;
import dev.thiru.bms.api.generated.Price;
import dev.thiru.bms.api.generated.Seat;
import dev.thiru.bms.api.generated.SeatRow;
import dev.thiru.bms.kernel.catalog.SeatMap;

/**
 * Maps catalog values to the catalog's JSON contract (SPEC 7), the records generated from {@code
 * api/openapi.yaml} ({@code dev.thiru.bms.api.generated}).
 */
final class CatalogDtos {

    private CatalogDtos() {}

    static MovieSummary summary(MovieDetail d) {
        return new MovieSummary(
                d.id(),
                d.title(),
                d.languages(),
                d.genres(),
                d.certificate(),
                d.durationMinutes(),
                d.releaseDate(),
                d.posterUrl());
    }

    static Price price(SeatMap.Price p) {
        return new Price(p.category(), p.name(), p.amount());
    }

    static dev.thiru.bms.api.generated.SeatMap seatMap(SeatMap m) {
        return new dev.thiru.bms.api.generated.SeatMap(
                m.showId(),
                m.version(),
                m.categories().stream().map(CatalogDtos::price).toList(),
                m.rows().stream()
                        .map(
                                r ->
                                        new SeatRow(
                                                r.row(),
                                                r.seats().stream()
                                                        .map(
                                                                s ->
                                                                        new Seat(
                                                                                s.id().toString(),
                                                                                s.number(),
                                                                                s.category(),
                                                                                s.status().name()))
                                                        .toList(),
                                                r.aisleAfter()))
                        .toList(),
                m.holdTtlSeconds());
    }
}
