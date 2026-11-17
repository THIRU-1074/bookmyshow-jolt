/**
 * Pure shared rules and value objects: the booking, hold and payment state machines, refund and
 * booking policy, catalog invariants, accounts and roles, {@code SeatId}, money in paise. Several
 * feature slices use the same rule, so it lives here once. Imports only the JDK (enforced by {@code
 * ArchitectureTest} and {@code TargetArchitectureTest}).
 */
package dev.thiru.bms.kernel;
