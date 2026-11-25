package com.documentplatform.appcheck;

import com.documentplatform.outbound.spring.OutboundProperties;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** P051 item 4: names apps use. They stay public and unchanged through 1.x; a type is added here, never taken away. */
class StableNamesTest {

    private static final String P = "com.documentplatform.";
    static final List<String> TYPES = List.of(
            P + "identity.signin.IssuerFetch", P + "identity.signin.OidcTestSignIn", P + "identity.signin.DevelopmentLicence",
            P + "identity.jdbc.LicenceSigningKey", P + "identity.admission.PlatformGroupAuthorization",
            P + "identity.admission.EntityMembershipResolver", P + "identity.jdbc.IdentitySchema",
            P + "identity.jdbc.PostgresEntityStore", P + "identity.jdbc.PostgresGrantHistory",
            P + "identity.jdbc.PostgresGroupGrantStore", P + "identity.jdbc.PostgresIdentityLookup",
            P + "identity.jdbc.PostgresLoginConnectionStore", P + "identity.jdbc.PostgresMachineBindingStore",
            P + "identity.jdbc.PostgresMachineClients", P + "identity.jdbc.PostgresMachineEntityBindings",
            P + "identity.jdbc.PostgresPushCallerDirectory", P + "licensing.jdbc.LicensingSchema",
            P + "licensing.jdbc.PostgresLastSeenStore", P + "licensing.jdbc.PostgresLicenceStore",
            P + "licensing.jdbc.PostgresNoticeLog", P + "licensing.jdbc.PostgresUsageLedger", P + "setup.SetupCommand",
            P + "identity.testkit.MockIssuer", P + "integration.profile.ProfileValidator",
            P + "integration.testkit.ProfileContract",
            P + "crypto.KeyService", P + "crypto.Unwrapped", P + "crypto.envelope.RecordCipher",
            P + "fields.values.FieldValues", P + "fields.values.SensitiveRead",
            P + "fields.FieldChange", P + "identity.store.LoginConnections", P + "notifications.approval.Approvals",
            P + "licensing.LicenceBuild");

    @Test
    void theTypesArePublic() throws Exception {
        for (String t : TYPES) assertTrue(Modifier.isPublic(Class.forName(t).getModifiers()), t);
    }

    @Test
    void theOutboundPropertiesKeepTheirNames() {
        assertEquals("platform.outbound", OutboundProperties.PREFIX);
        Set<String> names = new TreeSet<>();
        for (var m : OutboundProperties.class.getMethods()) if (m.getName().startsWith("set")) names.add(m.getName());
        for (var m : OutboundProperties.Proxy.class.getMethods()) if (m.getName().startsWith("set")) names.add("proxy." + m.getName());
        assertTrue(names.containsAll(Set.of("setInstallation", "setAllowInternalAddresses", "setAllowSignInNone",
                "setPrivateCaFile", "proxy.setHost", "proxy.setPort", "proxy.setBypass", "proxy.setUser",
                "proxy.setPasswordSecret")), names.toString());
    }
}
