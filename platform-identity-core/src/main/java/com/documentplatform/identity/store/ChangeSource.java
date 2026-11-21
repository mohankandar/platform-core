package com.documentplatform.identity.store;

/** Who changed a login connection: the setup command, the app's admin screen, or a one-off migration. */
public enum ChangeSource {
    SETUP,
    APP,
    MIGRATION
}
