package com.documentplatform.appcheck;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.fields.jdbc.PostgresFieldStore;
import com.documentplatform.fields.jdbc.PostgresValueStore;
import com.documentplatform.fields.values.FieldValues;
import com.documentplatform.fields.values.RecordRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P059 (LV-25.2, design note 1.5 test 9): the field stores in a Spring {@code @Transactional} method, with the
 * three lines of the fields install guide ({@code DataSourceUtils}).
 */
class FieldsInSpringTransactionTest {

    static final FieldScope A = FieldScope.of(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), "PRODUCT_A");
    static final CustomerId CUSTOMER = CustomerId.fromTenantId("TEN-TEST-0001");

    private final Db db = new Db().migrated();
    private final AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext();

    FieldsInSpringTransactionTest() {
        spring.registerBean(DataSource.class, () -> db.owner);
        spring.register(Config.class);
        spring.refresh();
        JdbcTemplate jdbc = spring.getBean(JdbcTemplate.class);
        jdbc.execute("create table app_case (id text primary key)");
        spring.getBean(FieldDefinitions.class).create(A, FieldSpec.builder("contract_number", "Contract number",
                FieldType.TEXT).required(true).primary(true).build(), "u-admin");
    }

    @AfterEach
    void close() {
        spring.close();
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean
        PlatformTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }

        @Bean
        JdbcTemplate jdbc(DataSource ds) {
            return new JdbcTemplate(ds);
        }

        @Bean
        FieldDefinitions definitions(DataSource ds) {
            PostgresValueStore store = new PostgresValueStore(ds);
            return new FieldDefinitions(new PostgresFieldStore(ds), store, Clock.systemUTC());
        }

        @Bean
        FieldValues values(DataSource ds) {
            InMemoryKeyService keys = new InMemoryKeyService();
            keys.createKeys(CUSTOMER);
            return new FieldValues(CUSTOMER, new PostgresFieldStore(ds), new PostgresValueStore(ds), keys,
                    Clock.systemUTC());
        }

        @Bean
        Cases cases(DataSource ds, JdbcTemplate jdbc, FieldValues values) {
            return new Cases(ds, jdbc, values);
        }

        @Bean
        Outer outer(JdbcTemplate jdbc, Cases cases) {
            return new Outer(jdbc, cases);
        }
    }

    /** The app's service: its own row and the case's field values in one transaction. */
    static class Cases {
        private final DataSource ds;
        private final JdbcTemplate jdbc;
        private final FieldValues values;

        Cases(DataSource ds, JdbcTemplate jdbc, FieldValues values) {
            this.ds = ds;
            this.jdbc = jdbc;
            this.values = values;
        }

        @Transactional
        public void save(String id, String contract, boolean fail) {
            jdbc.update("insert into app_case (id) values (?)", id);
            claim(id, contract);
            if (fail) {
                throw new IllegalStateException("the app's own step failed");
            }
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void saveOnItsOwn(String id, String contract) {
            jdbc.update("insert into app_case (id) values (?)", id);
            claim(id, contract);
        }

        private void claim(String id, String contract) {
            // The guide's helper: the connection of the current transaction, given back after the call.
            Connection c = DataSourceUtils.getConnection(ds);
            try {
                values.in(c).claim(RecordRef.of(A, "case", id),
                        Map.of("contract_number", List.of(FieldValue.text(contract))));
            } finally {
                DataSourceUtils.releaseConnection(c, ds);
            }
        }
    }

    static class Outer {
        private final JdbcTemplate jdbc;
        private final Cases cases;

        Outer(JdbcTemplate jdbc, Cases cases) {
            this.jdbc = jdbc;
            this.cases = cases;
        }

        @Transactional
        public void saveThenFail(String outerId, String innerId) {
            jdbc.update("insert into app_case (id) values (?)", outerId);
            cases.saveOnItsOwn(innerId, "IN-1");
            throw new IllegalStateException("the outer step failed");
        }
    }

    private long count(String table) {
        return spring.getBean(JdbcTemplate.class).queryForObject("select count(*) from " + table, Long.class);
    }

    @Test
    void anExceptionInTheTransactionalMethodRollsBackTheAppsRowAndTheFieldValues() {
        Cases cases = spring.getBean(Cases.class);
        assertThatThrownBy(() -> cases.save("c1", "AB-1", true)).isInstanceOf(IllegalStateException.class);
        assertThat(count("app_case")).isZero();
        assertThat(count("fld_record")).isZero();
        assertThat(count("fld_value")).isZero();

        cases.save("c2", "AB-2", false);
        assertThat(count("app_case")).isEqualTo(1);
        assertThat(count("fld_record")).isEqualTo(1);
    }

    @Test
    void requiresNewCommitsOnItsOwnWhileTheOuterTransactionRollsBack() {
        Outer outer = spring.getBean(Outer.class);
        assertThatThrownBy(() -> outer.saveThenFail("outer", "inner")).isInstanceOf(IllegalStateException.class);
        assertThat(spring.getBean(JdbcTemplate.class).queryForList("select id from app_case", String.class))
                .containsExactly("inner");
        assertThat(count("fld_record")).isEqualTo(1);
    }
}
