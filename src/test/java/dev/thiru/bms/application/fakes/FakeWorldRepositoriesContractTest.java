package dev.thiru.bms.application.fakes;

import dev.thiru.bms.contract.RepositoriesContract;
import dev.thiru.bms.domain.port.UnitOfWork;

/** The in-memory store every use case test runs on, held to the repository contract. */
class FakeWorldRepositoriesContractTest extends RepositoriesContract {

    @Override
    protected UnitOfWork newUnitOfWork() {
        return new FakeWorld();
    }
}
