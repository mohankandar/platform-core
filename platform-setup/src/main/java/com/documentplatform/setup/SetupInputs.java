package com.documentplatform.setup;

import com.documentplatform.identity.mapping.ClientBindingsFile;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceException;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.TrustedKeys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The checked input files of one run: everything that can be checked without the database. Built by
 * {@link #load}, which refuses with a SETUP_* code before anything else happens.
 *
 * @param mappingName the role mapping file's name, for messages
 * @param bindings    the client-binding file's rows, checked line by line; null when the sign-in file names no file
 * @param bindingsName the client-binding file's name, for messages; null without one
 */
record SetupInputs(String product, byte[] licenceFile, Licence licence, TrustedKeys keys, SigninFile signin,
                   SigninFile.Product productSignin, ProductRoles roles, byte[] mapping, String mappingName,
                   List<ClientBindingsFile.Row> bindings, String bindingsName, List<CheckedProfile> profiles,
                   Path configOut, boolean removeUnlistedGrants, boolean replaceConnection) {

    /** @throws SetupException with the first problem found, in the order the files are listed */
    static SetupInputs load(Map<String, String> o, boolean removeUnlisted, boolean replaceConnection) {
        String product = o.get("--product");
        TrustedKeys keys;
        try {
            keys = TrustedKeys.parse(text(o.get("--keys"), "--keys"));
        } catch (IllegalArgumentException e) {
            throw new SetupException(SetupRefusal.SETUP_KEYS_INVALID, "--keys: " + e.getMessage() + ".");
        }
        byte[] licenceFile = bytes(Path.of(o.get("--licence")), "--licence");
        Licence licence;
        try {
            licence = new LicenceVerifier(keys).verify(licenceFile, null);
        } catch (LicenceException e) {
            throw new SetupException(SetupRefusal.SETUP_LICENCE_REFUSED, e.getMessage());
        }
        Path signinPath = Path.of(o.get("--signin"));
        SigninFile signin = SigninFile.parse(text(o.get("--signin"), "--signin"));
        if (!signin.tenantId().equals(licence.tenantId())) {
            throw new SetupException(SetupRefusal.SETUP_WRONG_TENANT, "The sign-in file is for customer "
                    + signin.tenantId() + "; the licence " + licence.licenceId() + " is for " + licence.tenantId() + ".");
        }
        if (!licence.products().contains(product)) {
            throw new SetupException(SetupRefusal.SETUP_PRODUCT_NOT_IN_LICENCE, "The licence " + licence.licenceId()
                    + " does not list product " + product + "; it lists " + licence.products() + ".");
        }
        SigninFile.Product productSignin = signin.product(product).orElseThrow(() -> new SetupException(
                SetupRefusal.SETUP_PRODUCT_NOT_IN_SIGNIN, "The sign-in file has no entry for product " + product + "."));
        ProductRoles roles = ProductRoles.parse(text(o.get("--product-roles"), "--product-roles"), product);
        Path mappingPath = signinPath.toAbsolutePath().getParent().resolve(productSignin.roleMappingFile());
        byte[] mapping = bytes(mappingPath, "roleMappingFile of " + product);
        List<ClientBindingsFile.Row> bindings = null;
        String bindingsName = null;
        if (productSignin.clientBindingsFile() != null) {
            Path bindingsPath = signinPath.toAbsolutePath().getParent().resolve(productSignin.clientBindingsFile());
            bindingsName = bindingsPath.getFileName().toString();
            ClientBindingsFile.Result r = ClientBindingsFile.read(bytes(bindingsPath, "clientBindingsFile of "
                    + product), productSignin.sourceKeys());
            if (!r.valid()) throw bindingsInvalid(bindingsName, r.errors());
            bindings = r.rows();
        }
        List<CheckedProfile> profiles = new java.util.ArrayList<>();
        for (SigninFile.ProfileEntry e : productSignin.integrationProfiles()) {
            profiles.add(checkProfile(e, signinPath.toAbsolutePath().getParent().resolve(e.file())));
        }
        Path configOut = Path.of(o.getOrDefault("--config-out", ConfigFragment.DEFAULT_NAME));
        return new SetupInputs(product, licenceFile, licence, keys, signin, productSignin, roles, mapping,
                mappingPath.getFileName().toString(), bindings, bindingsName, List.copyOf(profiles), configOut, removeUnlisted,
                replaceConnection);
    }

    /** An integration profile that passed: its name, version, tag and file name, for the plan and the run log. */
    record CheckedProfile(String name, String version, String tag, String file) {
    }

    /**
     * Checks one profile named in the sign-in file: a tag is given, the file passes the profile format's validator,
     * the profile's own name is the entry's, and the tag ends with the profile's version. Nothing is stored.
     */
    static CheckedProfile checkProfile(SigninFile.ProfileEntry e, Path file) {
        String fileName = file.getFileName().toString();
        if (e.tag() == null) {
            throw new SetupException(SetupRefusal.SETUP_PROFILE_TAG_MISSING, "The sign-in file names integration "
                    + "profile " + e.name() + " (file " + fileName + ") without its release tag.");
        }
        com.documentplatform.integration.profile.ProfileValidator.Result r =
                com.documentplatform.integration.profile.ProfileLoader.check(file);
        if (!r.valid()) {
            throw new SetupException(SetupRefusal.SETUP_PROFILE_INVALID, "The integration profile file " + fileName
                    + " has " + r.problems().size() + " problem(s).", r.problems().stream()
                    .map(p -> fileName + " " + p).toList());
        }
        var profile = r.profile();
        if (!profile.name().equals(e.name())) {
            throw new SetupException(SetupRefusal.SETUP_PROFILE_INVALID, "The integration profile file " + fileName
                    + " is profile " + profile.name() + ", not " + e.name() + ".", List.of(fileName + " /name"));
        }
        if (!e.tag().endsWith(profile.version())) {
            throw new SetupException(SetupRefusal.SETUP_PROFILE_INVALID, "The integration profile file " + fileName
                    + " is version " + profile.version() + ", which the tag " + e.tag() + " does not name.",
                    List.of(fileName + " /version"));
        }
        return new CheckedProfile(profile.name(), profile.version(), e.tag(), fileName);
    }

    /** SETUP_CLIENT_BINDINGS_INVALID with one detail line per problem (line number, code, what is wrong). */
    static SetupException bindingsInvalid(String name, List<ClientBindingsFile.Problem> problems) {
        List<String> lines = problems.stream().map(ClientBindingsFile.Problem::display).toList();
        List<String> codes = problems.stream().map(p -> p.code().name()).distinct().toList();
        return new SetupException(SetupRefusal.SETUP_CLIENT_BINDINGS_INVALID, "The client-binding file " + name
                + " has " + problems.size() + " error(s) " + codes + ".", lines);
    }

    private static String text(String file, String option) {
        return new String(bytes(Path.of(file), option), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(Path file, String what) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException | SecurityException e) {
            throw new SetupException(SetupRefusal.SETUP_FILE_UNREADABLE, what + " " + file.getFileName()
                    + " cannot be read (" + e.getClass().getSimpleName() + ").");
        }
    }
}
