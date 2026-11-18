package dev.thiru.bms.testing;

import dev.thiru.bms.contract.IdGeneratorContract;
import dev.thiru.bms.ports.Ids;

class SequentialIdsContractTest extends IdGeneratorContract {

    @Override
    protected Ids newIds() {
        return new SequentialIds();
    }
}
