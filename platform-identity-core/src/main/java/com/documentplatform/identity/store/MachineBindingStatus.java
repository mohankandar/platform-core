package com.documentplatform.identity.store;

/** Whether an integration client binding is in force. INACTIVE bindings are kept, never deleted. */
public enum MachineBindingStatus {
    ACTIVE,
    INACTIVE
}
