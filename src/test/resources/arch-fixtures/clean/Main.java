package dev.thiru.bms;

import dev.thiru.bms.adapters.payment.MockPayments;

public final class Main {
    Object payments = new MockPayments();
    Object inline = new dev.thiru.bms.adapters.payment.MockPayments();
}
