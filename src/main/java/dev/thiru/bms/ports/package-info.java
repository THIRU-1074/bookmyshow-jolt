/**
 * Real I/O seams only (payment gateway, seat events and the live stream, password hashing, ids,
 * refresh tokens, the database transaction), one adapter each under {@code adapters}. No port per
 * entity. Ports import only {@code kernel} and the JDK; {@code java.sql.ResultSet} is allowed for
 * row mapping.
 */
package dev.thiru.bms.ports;
