package dev.thiru.bms.web;

import dev.thiru.bms.features.booking.BookingRoutes;
import dev.thiru.bms.ports.Payments;
import in.jolt.App;

/** The route table: the one web class that names slices. */
public final class WebModules {
    static void routes(App app, Payments payments) {}
}
