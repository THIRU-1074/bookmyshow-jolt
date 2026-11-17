package dev.thiru.bms.domain;

/**
 * Projection format; the label is the wire and database value (enum names cannot start with a
 * digit).
 */
public enum ShowFormat {
    TWO_D("2D"),
    THREE_D("3D"),
    IMAX("IMAX");

    private final String label;

    ShowFormat(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ShowFormat parse(String label) {
        for (ShowFormat f : values()) {
            if (f.label.equals(label)) {
                return f;
            }
        }
        throw DomainException.validation("format", "must be one of 2D, 3D, IMAX");
    }
}
