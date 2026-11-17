package dev.thiru.bms.adapters.ids;

import dev.thiru.bms.contract.IdGeneratorContract;
import dev.thiru.bms.domain.port.IdGenerator;

/** The production id generator, held to the id contract. */
class SecureIdsContractTest extends IdGeneratorContract {

    @Override
    protected IdGenerator newIds() {
        return new SecureIds();
    }
}
