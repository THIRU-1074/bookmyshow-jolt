package dev.thiru.bms;

final class Wiring {
    Object db = new dev.thiru.bms.adapters.db.SqliteDb();
}
