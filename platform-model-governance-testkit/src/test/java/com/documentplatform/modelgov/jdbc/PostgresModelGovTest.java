package com.documentplatform.modelgov.jdbc;

import com.documentplatform.modelgov.ModelGovContract;
import com.documentplatform.modelgov.ModelGovStore;

/** The rules over the Postgres store, logged in as the DML-only app user after the startup check. */
class PostgresModelGovTest extends ModelGovContract {

    @Override
    protected ModelGovStore newStore() {
        Pg db = Pg.migrated();
        var app = db.appUser("select, insert, update, delete");
        ModelGovSchema.verify(app);
        return new PostgresModelGovStore(app);
    }
}
