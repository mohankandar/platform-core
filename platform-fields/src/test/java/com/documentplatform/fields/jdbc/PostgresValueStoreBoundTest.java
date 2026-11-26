package com.documentplatform.fields.jdbc;

import com.documentplatform.fields.FieldStore;
import com.documentplatform.fields.values.ValueStore;

import javax.sql.DataSource;

/** P059, design note 1.5 test 8: every value store test again, each call bound to an app's transaction. */
class PostgresValueStoreBoundTest extends PostgresValueStoreTest {

    @Override
    FieldStore fieldStore(DataSource ds) {
        return BoundEachCall.definitions(ds);
    }

    @Override
    ValueStore valueStore(DataSource ds) {
        return BoundEachCall.values(ds);
    }
}
