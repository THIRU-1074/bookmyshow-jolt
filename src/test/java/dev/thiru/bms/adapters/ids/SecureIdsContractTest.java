package dev.thiru.bms.adapters.ids;

import dev.thiru.bms.contract.IdGeneratorContract;
import dev.thiru.bms.ports.Ids;

/** The production id generator, held to the id contract. */
class SecureIdsContractTest extends IdGeneratorContract {

    @Override
    protected Ids newIds() {
        return new SecureIds();
    }
}
