package dev.thiru.bms.kernel;

import java.util.Objects;

/** Comments may mention in.jolt.App or dev.thiru.bms.adapters.db.SqlDb freely. */
public record Money(long paise) {
    public Money {
        Objects.checkIndex(0, 1);
        String s = "dev.thiru.bms.adapters.db.SqlDb in a string is not a dependency";
    }
}
