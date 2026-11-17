package dev.thiru.bms.application.fakes;

import dev.thiru.bms.contract.IdGeneratorContract;
import dev.thiru.bms.ports.Ids;

/** FakeWorld's counting ids, held to the contract SecureIds meets. */
class FakeWorldIdGeneratorContractTest extends IdGeneratorContract {

    @Override
    protected Ids newIds() {
        return new FakeWorld();
    }
}
