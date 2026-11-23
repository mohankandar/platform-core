package com.documentplatform.integration;

/**
 * The platform's stable codes for integrations: the code of every attempt, health check and Test result, and of every
 * configuration problem. Logs, results and the app's audit carry these codes (and the destination's own code), never a
 * secret, a document's content or the destination's message text.
 */
public enum IntegrationCode implements com.documentplatform.errors.PlatformCode {

    // ---- One attempt: what happened ----
    /** Every step answered as expected. */
    INT_SENT,
    /** The destination already had it (a classification rule said so, for example a replay answer). */
    INT_ALREADY_RECEIVED,
    /** The destination refused the request (a 4xx other than sign-in); its own code is in the result. */
    INT_REFUSED,
    /** 404 without a destination code: the address or path is wrong, which is a set-up fault, not a refusal. */
    INT_ADDRESS_NOT_FOUND,
    /** The destination answered with a redirect; redirects are never followed. */
    INT_REDIRECT_REFUSED,
    /** The destination refused the sign-in (401 / 403). */
    INT_SIGN_IN_REFUSED,
    /** The destination is busy: wait (Retry-After when sent) and send less; no attempt used. */
    INT_BUSY,
    /** The destination is down or failing (5xx not classed as busy). */
    INT_OUTAGE,
    /** A 2xx answer that could not be read as the configuration expects (a value missing, not JSON, too long). */
    INT_UNCLEAR_ANSWER,
    /** The answer was larger than the integration's limit and was not read. */
    INT_RESPONSE_TOO_LARGE,
    /** An answer with a status no rule covers. */
    INT_UNEXPECTED_STATUS,
    /** The destination returned another checksum or size than was sent for a document. */
    INT_INTEGRITY_MISMATCH,
    /**
     * The health check says the destination is busy (for example 503 with a busy code or Retry-After): wait and send
     * fewer at once; it is not down, and no attempt is used.
     */
    INT_DESTINATION_BUSY,

    // ---- Could not connect ----
    INT_CANT_CONNECT,
    INT_HOST_UNKNOWN,
    INT_CONNECT_TIMEOUT,
    /** No answer within the read timeout. The request may have arrived: resend with the same idempotency key. */
    INT_TIMEOUT,
    /** The connection was lost before a full answer was read. */
    INT_CONNECTION_LOST,
    /** The TLS handshake failed: certificate not trusted, host name not in the certificate, protocol refused. */
    INT_TLS_FAILED,
    /** The address resolves to a blocked address (cloud metadata, link-local; internal when hosted). */
    INT_ADDRESS_BLOCKED,
    /** The outbound proxy refused the connection. */
    INT_PROXY_REFUSED,
    /** The outbound proxy asked for sign-in (407): the proxy user or password is wrong, or none is set. */
    INT_PROXY_SIGN_IN_REFUSED("The outbound proxy refused the sign-in (407). Check the proxy user and the password in "
            + "the secret store (platform.outbound.proxy.user, platform.outbound.proxy.password-secret)."),
    // ---- Client certificates (mutual TLS) ----
    /** The uploaded file is not a PKCS#12 or PEM file with one certificate and its private key (or it is too large). */
    INT_CREDENTIAL_FILE_INVALID("The certificate file is not a PKCS#12 (.p12, .pfx) or PEM file holding the client "
            + "certificate and its private key. Export the certificate together with its private key and upload it "
            + "again."),
    /** The PKCS#12 file's (or the keystore's) password is wrong, or the PEM key is encrypted. */
    INT_CREDENTIAL_PASSWORD_WRONG("The password of the certificate file or keystore is wrong (a PEM private key must "
            + "not be encrypted). Check the password, or export the key without a password as PEM."),
    /** The private key does not belong to the certificate. */
    INT_CREDENTIAL_KEY_MISMATCH("The private key does not belong to the certificate. Upload the certificate with its "
            + "own private key."),
    /** The certificate has expired. */
    INT_CREDENTIAL_EXPIRED("The client certificate has expired. Upload the renewed certificate as the next one and "
            + "switch to it."),
    /** The certificate is not valid yet. */
    INT_CREDENTIAL_NOT_YET_VALID("The client certificate is not valid yet (its start date is in the future). Keep it "
            + "as the next certificate and switch when it is valid."),
    /** Mutual TLS is configured, but there is no certificate (no current one; or no next one for a Test of next). */
    INT_CREDENTIAL_MISSING("The integration signs in with a client certificate, but none is set. Upload one, or point "
            + "to one in a keystore."),
    /** The key service could not seal or open the stored certificate. */
    INT_CREDENTIAL_KEY_SERVICE_FAILED("The key service could not encrypt or decrypt the stored client certificate. "
            + "Check the key service (its health), then try again."),
    /** The destination refused the client certificate (a TLS alert: bad, unknown, expired or revoked certificate). */
    INT_CREDENTIAL_REFUSED("The destination refused the client certificate. Check with its owner that they trust "
            + "this certificate (subject, issuer and fingerprint as shown) and that it is not revoked."),
    /** The keystore file cannot be read (missing, no permission) or is not a keystore. */
    INT_CREDENTIAL_KEYSTORE_UNREADABLE("The keystore file cannot be read. Check the path and that the app's service "
            + "account may read it."),
    /** The keystore has no entry with a private key under that alias. */
    INT_CREDENTIAL_ALIAS_NOT_FOUND("The keystore has no private key entry under this alias. Check the alias "
            + "(keytool -list shows them)."),
    /** There is already a next certificate: switch to it or discard it first. */
    INT_CREDENTIAL_NEXT_EXISTS,
    /** There is no next certificate to switch to or to discard. */
    INT_CREDENTIAL_NO_NEXT,
    /** Someone else changed the certificates first: reload and try again. */
    INT_CREDENTIAL_CONFLICT,

    // ---- Folder export ----
    /** The target folder does not exist (or is not a folder). */
    INT_FOLDER_NOT_FOUND("The export folder does not exist or is not a folder. Create it, or correct the folder in the "
            + "integration's configuration."),
    /** The service account may not create folders or files in the target folder. */
    INT_FOLDER_NO_PERMISSION("The app's service account may not write to the export folder. Give it the right to "
            + "create, write, rename and delete folders and files there."),
    /** The disk or the account's quota is full. */
    INT_FOLDER_FULL("The export folder's disk or quota is full."),
    /** The network share cannot be reached. */
    INT_FOLDER_UNREACHABLE("The export folder's network share cannot be reached (server down, share removed, network "
            + "or sign-in problem)."),
    /** A package folder of that name already exists with another manifest. */
    INT_FOLDER_EXISTS_DIFFERENT("A package folder of this name already exists in the export folder with other "
            + "content. Nothing was changed; remove or rename the old folder, or check the folder name template."),
    /** Writing failed for another reason; only the package's .partial folder was touched. */
    INT_FOLDER_WRITE_FAILED("Writing to the export folder failed. Only the package's .partial folder was touched."),

    // ---- Intake: API polling ----
    /** The list answer has no array of items where the configuration says. */
    INT_POLL_ITEMS_MISSING("The source's list answer has no list of items where the configuration expects it. Check "
            + "the items pointer against the source's API."),
    /** An item without a usable source item ID (missing, not a value, too long); it is reported and left alone. */
    INT_POLL_ITEM_ID_MISSING("An item in the source's list has no usable ID where the configuration expects it. Check "
            + "the item ID pointer against the source's API."),
    /** The next page's cursor or address cannot be used (not a value, or an address on another host). */
    INT_POLL_NEXT_PAGE_INVALID("The source gave a next page that cannot be followed (an address on another server, or "
            + "a cursor that is not a value). Check the paging settings against the source's API."),
    /** The downloaded document could not be kept in the temporary folder (full, no permission). */
    INT_POLL_STORAGE_FAILED("A downloaded document could not be kept in the app's temporary folder. Check its free "
            + "space and the service account's rights there."),
    /** The app's intake handler failed (an error, not an answer): the pass stopped, nothing was acknowledged. */
    INT_POLL_HANDLER_FAILED,

    // ---- Intake: watched folder or share ----
    /** The watched folder does not exist (or is not a folder). */
    INT_WATCH_ROOT_NOT_FOUND("The watched folder does not exist or is not a folder. Create it, or correct the folder "
            + "in the integration's configuration."),
    /** The service account may not read, create, rename or delete in the watched folder. */
    INT_WATCH_NO_PERMISSION("The app's service account may not work in the watched folder. Give it the right to list, "
            + "read, create, rename and delete folders and files there."),
    /** The network share cannot be reached (before or during a pass). */
    INT_WATCH_SHARE_UNREACHABLE("The watched folder's network share cannot be reached (server down, share removed, "
            + "network or sign-in problem). Drops already claimed are released by themselves after the claim time."),
    /** The trigger file cannot be read: malformed JSON or XML, too large, too deep, or a DTD or entity in XML. */
    INT_WATCH_TRIGGER_UNREADABLE("The drop's trigger file cannot be read (it is not well-formed JSON or XML, it is too "
            + "large or too deeply nested, or the XML has a DTD or entities). The drop was moved to _problem; fix the "
            + "file and put the drop back."),
    /** The claim was lost: another app instance took the drop first, or a stuck claim was released meanwhile. */
    INT_WATCH_CLAIM_LOST,
    /** Moving a drop (to _processing, _archive, _problem or _quarantine) failed. */
    INT_WATCH_MOVE_FAILED("A drop could not be moved inside the watched folder. Check the service account's right to "
            + "rename and delete there, and that no other program holds the files open."),
    /** A file of the drop changed after it was claimed (its checksum or size differs). */
    INT_WATCH_FILE_CHANGED("A file of the drop changed after the app took it. The customer's process must finish "
            + "writing every file before it writes the trigger file. The drop was moved to _problem."),
    /**
     * The drop cannot be taken as it is: a link (symbolic link or junction) or a folder inside it, more files than
     * the limit, a file larger than the limit, or a trigger name that two files match.
     */
    INT_WATCH_DROP_INVALID("The drop cannot be taken: it holds a link or a folder, more files than allowed, a file "
            + "larger than allowed, or more than one trigger file. The drop was moved to _problem."),
    /** Files present without their trigger after the incomplete-drop time (reported once; nothing is moved). */
    INT_WATCH_INCOMPLETE("A drop has files but no trigger file after the waiting time. The customer's process may have "
            + "stopped before writing the trigger file. Nothing was moved."),
    /** The app refused the drop; its own code is in problem.json. */
    INT_WATCH_REFUSED,
    /** The app's intake handler failed (an error, not an answer): the drop was put back, to be tried again. */
    INT_WATCH_HANDLER_FAILED,

    // ---- Virus scan ----
    /** The scanner found a virus: the file is quarantined and never handed over. */
    INT_SCAN_INFECTED("The virus scanner found a virus in an incoming file. The file was quarantined and not "
            + "processed. Tell the sender; delete the quarantined copy after checking it."),
    /** The scanner answered with an error or something that is not a scan result. The file is held, never let through. */
    INT_SCAN_FAILED("The virus scanner could not scan an incoming file (it answered with an error). The file is held "
            + "and scanned again at the next pass. Check the scanner's log."),
    /** The scanner cannot be reached or did not answer in time. The file is held, never let through. */
    INT_SCAN_UNREACHABLE("The virus scanner cannot be reached or did not answer in time. Incoming files are held until "
            + "it answers. Check the scanner's address, port and firewall."),

    // ---- Sign-in bundle ----
    INT_SECRET_NOT_FOUND,
    INT_SECRET_UNAVAILABLE,
    /** Sign-in NONE is used where it is not allowed (hosted, or not switched on for this installation). */
    INT_SIGN_IN_NONE_NOT_ALLOWED,
    /** The token address could not be reached, or answered 429 / 5xx. */
    INT_TOKEN_UNREACHABLE,
    /** The token address refused the request (for example an unknown scope or audience). */
    INT_TOKEN_REFUSED,
    /** The token address refused the client's credential ({@code invalid_client}, {@code unauthorized_client}). */
    INT_TOKEN_CREDENTIAL_REFUSED,
    /** The token address does not know the client or the directory. */
    INT_TOKEN_CLIENT_UNKNOWN,
    /** The certificate credential in the secret store is not a certificate with its matching private key. */
    INT_TOKEN_CREDENTIAL_INVALID,
    /** The certificate credential has expired. */
    INT_TOKEN_CREDENTIAL_EXPIRED,
    /** The token address answered 200 with something that is not a bearer token. */
    INT_TOKEN_UNEXPECTED_REPLY,
    /** The token lacks a claim the configuration requires, or is not a JWT whose claims can be read. */
    INT_TOKEN_CLAIMS_MISSING,

    // ---- Before sending ----
    /** The integration is switched off. */
    INT_DISABLED,
    /** This kind of integration is not run by the call engine (yet). */
    INT_KIND_NOT_SUPPORTED,
    /** A value a template needs was not supplied. */
    INT_VALUE_MISSING,
    /** A value cannot be placed where the template puts it (control characters in a header, a non-number). */
    INT_VALUE_NOT_ALLOWED,
    /** A document is larger than the integration's limit. */
    INT_FILE_TOO_LARGE,
    /** More documents than the integration's limit. */
    INT_TOO_MANY_DOCUMENTS,
    /** The documents together are larger than the integration's limit. */
    INT_PACKAGE_TOO_LARGE,
    /** A sensitive case field would be sent although the configuration does not allow that key. */
    INT_SENSITIVE_FIELD_NOT_ALLOWED,
    /** The delivery's case field values are not a readable platform-field-values/1 document. */
    INT_FIELD_VALUES_INVALID,
    /** A document's content could not be read from the app. */
    INT_CONTENT_UNREADABLE,
    /** A stored integration configuration is not readable (its stored JSON is damaged or of an unknown form). */
    INT_CONFIG_UNREADABLE,
    /** The address is not https and plain http is not allowed for this installation. */
    INT_ADDRESS_NOT_SECURE,

    // ---- Accepted, check later (202) ----
    /** The destination accepted the request for later processing (202): check its status later. */
    INT_ACCEPTED,
    /** The destination's status says the work failed. Its own code, if any, is in the result. */
    INT_STATUS_FAILED,
    /** A 202 answer without the status reference the configuration says where to find. */
    INT_STATUS_REFERENCE_MISSING,
    /** A status answer whose state is none of the configured done, failed or still-working values. */
    INT_STATUS_UNKNOWN,

    // ---- Callbacks ----
    /** Callback in: the signature header is missing, malformed, or matches none of the secrets. */
    INT_CALLBACK_SIGNATURE,
    /** Callback in: its time is outside the allowed window (old, or too far ahead). */
    INT_CALLBACK_EXPIRED,
    /** Callback in: this callback ID was already accepted. */
    INT_CALLBACK_REPLAYED,
    /** Callback out: the body is not one JSON object, or it is larger than the limit. */
    INT_CALLBACK_BODY_INVALID,
    /** Callback out: no callback with this ID (to resend). */
    INT_CALLBACK_NOT_FOUND,

    // ---- Health and Test ----
    INT_HEALTHY,
    /** Test action in check-only mode: health and sign-in worked; no step was sent. */
    INT_TEST_CHECKED,

    // ---- Configuration (when it is saved) ----
    INT_CONFIG_INVALID,
    INT_CONFIG_ADDRESS_INVALID,
    INT_CONFIG_ADDRESS_NOT_SECURE,
    INT_CONFIG_PLACEHOLDER_UNKNOWN,
    /** A placeholder for a secret, sign-in or document content. */
    INT_CONFIG_PLACEHOLDER_FORBIDDEN,
    /** A placeholder in the query string or the host of an address. */
    INT_CONFIG_PLACEHOLDER_IN_QUERY,
    /** A placeholder where its value cannot go (for example text outside a JSON string). */
    INT_CONFIG_PLACEHOLDER_PLACE,
    /** A step placeholder naming a step that does not run earlier, or a value that step does not read. */
    INT_CONFIG_STEP_VALUE_UNKNOWN,
    INT_CONFIG_TEMPLATE_INVALID,
    INT_CONFIG_BODY_NOT_JSON,
    /** A header that only the sign-in bundle or the HTTP layer may set (Authorization, Host, ...). */
    INT_CONFIG_HEADER_RESERVED,
    INT_CONFIG_HEADER_INVALID,
    INT_CONFIG_SIGN_IN_NONE_HOSTED,
    INT_CONFIG_SIGN_IN_NONE_OFF,
    /** Warning (not an error): sign-in NONE is in use on this on-premises installation. */
    INT_CONFIG_SIGN_IN_NONE_WARNING,
    /** A {field:<key>} placeholder names a field the entity does not have (or no field list was given). */
    INT_CONFIG_FIELD_UNKNOWN,
    /** A sensitive field is mapped without being allowed for this integration, or outside a JSON body. */
    INT_CONFIG_SENSITIVE_FIELD_NOT_ALLOWED,
    /** A setting (profile setting, required token claims) is not valid. */
    INT_CONFIG_SETTING_INVALID,
    /** A multipart or integrity part of a step is not valid. */
    INT_CONFIG_BODY_INVALID,
    /**
     * An "omit when missing" placeholder ({@code {name?}}) stands anywhere but as the whole value of an object member
     * in a JSON body.
     */
    INT_CONFIG_OMIT_PLACE,
    /**
     * Folder export: the target folder is not an absolute local or share path, or a folder or file name template is
     * not one path segment of allowed characters and placeholders.
     */
    INT_CONFIG_FOLDER_INVALID,
    /** Folder export on the hosted service: never allowed (a folder is the customer's own system). */
    INT_CONFIG_FOLDER_HOSTED,
    /**
     * Mutual TLS: the address is not https, or the sign-in alongside the certificate is not allowed (NONE, or mutual
     * TLS twice), or the certificate is to be used for a token request without OAuth 2.0.
     */
    INT_CONFIG_MUTUAL_TLS_INVALID,
    /** Callback: no signing secret, a callback setting on another kind, or steps on a callback. */
    INT_CONFIG_CALLBACK_INVALID,
    /**
     * "Accepted, check later": not on the last step run once, a pointer that is not valid, no status path (or one
     * with a Location reference), no done or failed values, or the same value in two lists.
     */
    INT_CONFIG_ACCEPTED_INVALID,
    /**
     * API polling: no poll source, or one whose steps, pointers, paging or values are not valid (a body on a GET, a
     * placeholder where it cannot go, a page size or page count out of range).
     */
    INT_CONFIG_POLL_INVALID,
    /**
     * Watched folder: no source, a root that is not an absolute local or share path, a trigger pattern that is not
     * valid, a mapping that is not a JSON pointer or XML path, or times and limits out of range; or the hosted service.
     */
    INT_CONFIG_WATCH_INVALID,
    INT_CONFIG_RULE_INVALID,
    INT_CONFIG_STEPS_INVALID,
    INT_CONFIG_VERSION_CONFLICT,

    // ---- Profiles (platform-integration-profile/1), from ProfileValidator; each with a JSON pointer ----
    /** The profile file cannot be read. */
    INT_PROFILE_UNREADABLE,
    /** The profile is not JSON (or not one object). */
    INT_PROFILE_NOT_JSON,
    /** The profile's format is not one this version knows (only platform-integration-profile/1). */
    INT_PROFILE_FORMAT_UNKNOWN,
    /** The profile does not fit the format's JSON Schema (a field missing, unknown, of another type or shape). */
    INT_PROFILE_SCHEMA,
    /** Steps: neither steps nor a packaging preset (or both), a preset missing its parts, or steps that cannot run. */
    INT_PROFILE_STEPS,
    /** A template: an unknown or forbidden placeholder, one in the query string, a body that is not JSON. */
    INT_PROFILE_TEMPLATE,
    /** A header only the sign-in bundle or HTTP may set, or a header name that is not valid. */
    INT_PROFILE_HEADER,
    /** A classification rule that is not valid. */
    INT_PROFILE_RULE,
    /** A template uses {value.<name>} that the profile's values do not declare. */
    INT_PROFILE_VALUE_UNDECLARED,
    /** A template uses {doc.attr.<name>} not declared, or an attribute's default is not among its values. */
    INT_PROFILE_ATTRIBUTE,
    /** The fields target names no step, or that step's body has no "<object>":{} to fill. */
    INT_PROFILE_FIELDS,
    /** A setting's default is not one of its values, or a value stands for an engine setting that does not exist. */
    INT_PROFILE_SETTING,
    /** Sign-in: an API key without its header name. */
    INT_PROFILE_SIGN_IN,
    /** Installation data in a profile: an address, something that looks like a secret, or a case field key. */
    INT_PROFILE_INSTALLATION_DATA,
    INT_CONFIG_NOT_FOUND,
    INT_CONFIG_EXISTS;

    private final String message;

    IntegrationCode() {
        this(null);
    }

    IntegrationCode(String message) {
        this.message = message;
    }

    /**
     * What to tell the customer's IT, for the codes they act on (proxy sign-in, folder export); else the code's name.
     * Never holds a value, address or secret.
     */
    public String message() {
        return message == null ? name() : message;
    }

    /** Whether {@link #message()} is more than the name. */
    public boolean hasMessage() {
        return message != null;
    }

    /**
     * The integration code of an outbound failure ({@code platform-outbound}): the code of the same name, so the
     * integrations' results are what they were before the outbound rules became their own module.
     */
    public static IntegrationCode of(com.documentplatform.outbound.OutboundCode code) {
        return switch (code) {
            case OUT_CANT_CONNECT -> INT_CANT_CONNECT;
            case OUT_HOST_UNKNOWN -> INT_HOST_UNKNOWN;
            case OUT_CONNECT_TIMEOUT -> INT_CONNECT_TIMEOUT;
            case OUT_TIMEOUT -> INT_TIMEOUT;
            case OUT_CONNECTION_LOST -> INT_CONNECTION_LOST;
            case OUT_TLS_FAILED -> INT_TLS_FAILED;
            case OUT_ADDRESS_BLOCKED -> INT_ADDRESS_BLOCKED;
            case OUT_ADDRESS_NOT_SECURE -> INT_ADDRESS_NOT_SECURE;
            case OUT_PROXY_REFUSED -> INT_PROXY_REFUSED;
            case OUT_PROXY_SIGN_IN_REFUSED -> INT_PROXY_SIGN_IN_REFUSED;
            case OUT_CREDENTIAL_REFUSED -> INT_CREDENTIAL_REFUSED;
            case OUT_SECRET_NOT_FOUND -> INT_SECRET_NOT_FOUND;
            case OUT_SECRET_UNAVAILABLE -> INT_SECRET_UNAVAILABLE;
            case OUT_VALUE_NOT_ALLOWED -> INT_VALUE_NOT_ALLOWED;
        };
    }
}
