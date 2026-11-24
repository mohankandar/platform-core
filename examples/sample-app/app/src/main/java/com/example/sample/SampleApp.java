package com.example.sample;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.vault.VaultClient;
import com.documentplatform.eraselog.EraseHandler;
import com.documentplatform.eraselog.fields.CaseFieldsEraseHandler;
import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.jdbc.FieldSchema;
import com.documentplatform.fields.jdbc.PostgresFieldStore;
import com.documentplatform.fields.jdbc.PostgresValueStore;
import com.documentplatform.fields.values.FieldValues;
import com.documentplatform.identity.jdbc.IdentitySchema;
import com.documentplatform.identity.jdbc.LicenceSlots;
import com.documentplatform.identity.jdbc.PostgresEntityStore;
import com.documentplatform.identity.store.SlotActivation;
import com.documentplatform.integration.store.ConfigChange;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.EventKind;
import com.documentplatform.notifications.EventType;
import com.documentplatform.notifications.Recipient;
import com.documentplatform.notifications.RecipientResolver;
import com.documentplatform.notifications.RoleId;
import com.documentplatform.notifications.Schedule;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.licensing.spring.notifications.LicensingEvents;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * The sample app. The first word chooses what runs: {@code schema} (the schema step), {@code setup} (the setup wrapper),
 * {@code licence} (the licence command); anything else starts the app.
 */
@SpringBootApplication
public class SampleApp {

    static final RoleId IT_ADMIN = RoleId.of("it-admin");
    static final RoleId BUSINESS_ADMIN = RoleId.of("business-admin");
    static final String HELLO = "sample.hello";

    public static void main(String[] args) {
        String first = args.length == 0 ? "" : args[0];
        String[] rest = args.length == 0 ? args : Arrays.copyOfRange(args, 1, args.length);
        switch (first) {
            case "schema" -> SchemaStep.main(rest);
            case "setup" -> com.documentplatform.setup.SetupCommand.main(rest);
            case "licence" -> com.documentplatform.licensing.command.LicenceCommand.main(rest);
            default -> SpringApplication.run(SampleApp.class, args);
        }
    }

    /** Health without details for probes; everything else for the monitoring user of the installation. */
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a.requestMatchers("/actuator/health/**").permitAll().anyRequest()
                        .hasRole("MONITORING"))
                .httpBasic(Customizer.withDefaults()).csrf(c -> c.disable()).build();
    }

    @Bean
    UserDetailsService users(Environment env) {
        return new InMemoryUserDetailsManager(User.withUsername("monitoring")
                .password("{noop}" + env.getRequiredProperty("sample.monitoring-password")).roles("MONITORING").build());
    }

    /** Secrets from the key service's KV store, under secret/data/platform/ (the policy platform-crypto-app). */
    @Bean
    SecretResolver secrets(VaultClient vault) {
        return ref -> {
            VaultClient.Secret s = vault.readSecret("secret", "platform/" + ref.value());
            return Optional.ofNullable(s.get("value")).map(Secret::of);
        };
    }

    @Bean
    IntegrationAudit integrationAudit() {
        System.Logger log = System.getLogger("sample.audit");
        return new IntegrationAudit() {
            @Override
            public void changed(ConfigChange change) {
                log.log(System.Logger.Level.INFO, "integration changed: {0}", change.integrationId());
            }

            @Override
            public void tested(AttemptResult result, String by) {
                log.log(System.Logger.Level.INFO, "integration tested: {0}", result.code());
            }
        };
    }

    @Bean
    EventCatalogue catalogue() {
        return LicensingEvents.register(EventCatalogue.builder(IT_ADMIN)
                .register(EventType.builder(HELLO, "Sample message", EventKind.TECHNICAL).schedule(Schedule.immediately())
                        .recipients(IT_ADMIN).build()), IT_ADMIN, BUSINESS_ADMIN).build();
    }

    @Bean
    RecipientResolver recipients() {
        return (tenant, entity, role) -> Set.of(new Recipient("u-it", "it@sample.example"));
    }

    /** docs/install/fields.md section 5. */
    @Bean
    PostgresFieldStore fieldStore(DataSource ds) {
        FieldSchema.verify(ds);
        return new PostgresFieldStore(ds);
    }

    @Bean
    PostgresValueStore valueStore(DataSource ds) {
        return new PostgresValueStore(ds);
    }

    @Bean
    FieldDefinitions fieldDefinitions(PostgresFieldStore fields, PostgresValueStore values) {
        return new FieldDefinitions(fields, values);
    }

    @Bean
    FieldValues fieldValues(CustomerId customer, PostgresFieldStore fields, PostgresValueStore values, KeyService keys) {
        return new FieldValues(customer, fields, values, keys, Clock.systemUTC());
    }

    @Bean
    EraseHandler caseFieldsEraseHandler(FieldValues values, PostgresValueStore store) {
        return new CaseFieldsEraseHandler(values, store);
    }

    /** docs/install/identity.md section 4. */
    @Bean
    SlotActivation slotActivation(DataSource ds, LicenceService licence) {
        IdentitySchema.verify(ds);
        return new SlotActivation(new PostgresEntityStore(ds), LicenceSlots.of(licence), Clock.systemUTC());
    }
}
