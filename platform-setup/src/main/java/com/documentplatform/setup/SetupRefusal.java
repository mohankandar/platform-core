package com.documentplatform.setup;

/**
 * Why setup stopped. Every code has a stable name and says what to do. Messages name files, fields, IDs and codes,
 * never secrets or file contents beyond IDs.
 */
public enum SetupRefusal {
    SETUP_BAD_OPTION(SetupCommand.EXIT_REFUSED, "Correct the options; 'setup' without options lists them."),
    SETUP_FILE_UNREADABLE(SetupCommand.EXIT_REFUSED, "Check the path and that the file can be read."),
    SETUP_KEYS_INVALID(SetupCommand.EXIT_REFUSED, "Use the trusted-keys.json shipped with this release, unchanged."),
    SETUP_LICENCE_REFUSED(SetupCommand.EXIT_REFUSED, "Use the licence file you received, unchanged, with the "
            + "trusted-keys.json of this release. A demo licence (development key) is never accepted by a production "
            + "installation."),
    SETUP_SIGNIN_INVALID(SetupCommand.EXIT_REFUSED, "Correct the sign-in file (format platform-signin/1). It holds "
            + "no secrets: client secrets are never written in it."),
    /** A time zone in the sign-in file is not an IANA name. */
    SETUP_TIME_ZONE_INVALID(SetupCommand.EXIT_REFUSED, "Write time zones as IANA names such as America/New_York or "
            + "Europe/London (not offsets such as -05:00, which do not follow daylight saving)."),
    /** The sign-in file gives a time zone for an entity slot the licence does not list. */
    SETUP_TIME_ZONE_SLOT_NOT_IN_LICENCE(SetupCommand.EXIT_REFUSED, "Give time zones in entityTimeZones only for "
            + "entity slots the licence lists."),
    SETUP_MODE_NOT_SUPPORTED(SetupCommand.EXIT_REFUSED, "This version sets up direct sign-in only (mode \"direct\")."),
    SETUP_WRONG_TENANT(SetupCommand.EXIT_REFUSED, "The sign-in file must name the customer (tenantId) of the licence; "
            + "use the sign-in file made for this customer."),
    SETUP_PRODUCT_NOT_IN_LICENCE(SetupCommand.EXIT_REFUSED, "Run setup for a product the licence lists, or ask for a "
            + "licence that includes it."),
    SETUP_PRODUCT_NOT_IN_SIGNIN(SetupCommand.EXIT_REFUSED, "Add the product (browserClientId, apiAudience, "
            + "roleMappingFile) to the sign-in file."),
    SETUP_PRODUCT_ROLES_INVALID(SetupCommand.EXIT_REFUSED, "Use the product-roles.json shipped with this product's "
            + "release, for the product given with --product."),
    SETUP_MAPPING_INVALID(SetupCommand.EXIT_REFUSED, "Correct the role mapping file at the lines listed; nothing was "
            + "written."),
    SETUP_DB_SETTINGS_MISSING(SetupCommand.EXIT_REFUSED, "Set " + SetupCommand.DB_URL + ", " + SetupCommand.DB_USER
            + " and " + SetupCommand.DB_PASSWORD + " in the environment (the app's everyday database user is enough)."),
    SETUP_DB_UNREACHABLE(SetupCommand.EXIT_REFUSED, "Check the database address, user and password in the "
            + "environment, and that the database is running."),
    SETUP_TABLES_NOT_READY(SetupCommand.EXIT_REFUSED, "Run the app's schema step for this release (licensing and "
            + "identity tables) first; setup never creates tables."),
    SETUP_OTHER_CUSTOMER_INSTALLED(SetupCommand.EXIT_REFUSED, "This database holds another customer's records. Check "
            + "that the database settings point to this customer's installation."),
    SETUP_LICENCE_OLDER(SetupCommand.EXIT_REFUSED, "A newer licence is already stored; use the newest licence file."),
    SETUP_LICENCE_VERSION_CONFLICT(SetupCommand.EXIT_REFUSED, "Another licence with the same version is already "
            + "stored; ask us for the correct licence file."),
    SETUP_CONFIG_OTHER_TENANT(SetupCommand.EXIT_REFUSED, "The config fragment names another customer; check --config-out "
            + "points to this installation's file."),
    /** No (or more than one) key service login, or no key service address. */
    SETUP_KEY_SERVICE_SETTINGS_MISSING(SetupCommand.EXIT_REFUSED, "Set " + SetupKeys.VAULT_ADDR + " and one login ("
            + SetupKeys.VAULT_TOKEN + ", " + SetupKeys.VAULT_TOKEN_FILE + ", or " + SetupKeys.VAULT_ROLE_ID + " with "
            + SetupKeys.VAULT_SECRET_ID + ") with the platform-crypto-setup policy, in the environment of this run "
            + "only; or run with --no-keys if this installation keeps no sensitive values."),
    /** The key service could not be reached, refused the login, or refused to read or create the keys. */
    SETUP_KEY_SERVICE_FAILED(SetupCommand.EXIT_REFUSED, "Check the key service address, the login and that its policy "
            + "is platform-crypto-setup (the CRYPTO_ code names the cause), then run setup again. Nothing was "
            + "changed in the database."),
    /** The database part failed and was rolled back; nothing was changed. */
    SETUP_FAILED(SetupCommand.EXIT_FAILED, "Nothing was changed in the database. Fix the cause and run setup again; "
            + "it does the whole run."),
    /** The database part was committed but the config fragment could not be written. */
    SETUP_CONFIG_NOT_WRITTEN(SetupCommand.EXIT_FAILED, "The database part is done. Make the config fragment's folder "
            + "writable and run setup again; it finishes what was missing.");

    private final int exitCode;
    private final String action;

    SetupRefusal(int exitCode, String action) {
        this.exitCode = exitCode;
        this.action = action;
    }

    public int exitCode() {
        return exitCode;
    }

    /** What the person running setup should do. */
    public String action() {
        return action;
    }
}
