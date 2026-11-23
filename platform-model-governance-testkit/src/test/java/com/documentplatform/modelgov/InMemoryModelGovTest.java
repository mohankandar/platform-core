package com.documentplatform.modelgov;

import com.documentplatform.modelgov.testkit.InMemoryModelGovStore;

/** The rules over the in-memory store. */
class InMemoryModelGovTest extends ModelGovContract {

    @Override
    protected ModelGovStore newStore() {
        return new InMemoryModelGovStore();
    }
}
