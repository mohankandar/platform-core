package com.documentplatform.fields.jdbc;

import com.documentplatform.fields.FieldStore;

import javax.sql.DataSource;

/** P059, design note 1.5 test 8: every field store test again, each call bound to an app's transaction. */
class PostgresFieldStoreBoundTest extends PostgresFieldStoreTest {

    @Override
    FieldStore fieldStore(DataSource ds) {
        return BoundEachCall.definitions(ds);
    }
}
