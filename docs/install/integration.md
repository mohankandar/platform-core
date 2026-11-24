# Integrations — install guide for IT

**Needs:** Spring Boot 4.2 and Java 21 in the app (`docs/design/platform-baseline.md`).

The app sends documents to your other systems through integrations. It sends them to an API (API push), or it
writes them into a folder or network share with a manifest file (folder export). Each integration is set up in the
app's integration screens, and every change is kept as a new version. This page lists what the installation itself
needs: the settings below (module `platform-integration-spring`), the outbound proxy, your private certificate
authority, the profile files, the rights on an export share, client certificates, signed callbacks to your own
systems, and the intake side: polling an API, a watched folder or share, and the virus scanner. It ends with every
code you can see, and what to do about it.

Secrets never go into these settings or into an integration's configuration. Only a **reference** goes there: the
name under which the secret is kept in your secret store, for example `integration/proxy-password`. The app reads the
secret at the moment it is needed, so a changed password works without a restart.

## 1. App settings (`platform.outbound.*` and `platform.integration.*`)

| Property | Default | |
|---|---|---|
| `platform.integration.enabled` | `true` | `false` switches integrations off |
| `platform.outbound.installation` | — | **required**: `ON_PREMISES` (your own systems) or `HOSTED` (our hosted service) |
| `platform.outbound.allow-internal-addresses` | `false` | on premises only: the app may call addresses on your internal network (loopback and private ranges) and plain `http` there |
| `platform.outbound.allow-sign-in-none` | `false` | on premises only: an integration may call without signing in (warned and audited) |
| `platform.outbound.private-ca-file` | none | your private certificate authorities, one PEM file (section 3) |
| `platform.outbound.proxy.host`, `.port` | none | your outbound proxy (section 2) |
| `platform.outbound.proxy.bypass` | none | hosts that do not use the proxy, comma-separated: an exact name (`files01`) or a suffix (`.corp.example`) |
| `platform.outbound.proxy.user` | none | the proxy's sign-in user, when the proxy asks for one |
| `platform.outbound.proxy.password-secret` | none | where the proxy password is kept in the secret store (a reference, never the password) |
| `platform.integration.limits.connect-timeout` | `10s` | default for new integrations: 1 second to 2 minutes |
| `platform.integration.limits.read-timeout` | `120s` | default: 1 second to 30 minutes. For a folder export, this is the time allowed for writing one file |
| `platform.integration.limits.max-file-size` | `250MB` | default: the largest document |
| `platform.integration.limits.max-documents` | `100` | default: the most documents in one package |
| `platform.integration.limits.max-response-size` | `1MB` | default: the largest answer read from an API |
| `platform.integration.limits.max-package-size` | `2GB` | default: the largest package |
| `platform.integration.profile-folder` | none | the folder where you put profile files (section 5) |
| `platform.integration.health.enabled` | `true` | one health check per integration (section 7) |
| `platform.integration.health.cache-for` | `60s` | how long one health result is kept: 5 seconds to 1 hour |
| `platform.integration.credentials.expiry-warning-days` | `30,7,1` | when the app warns before a client certificate ends (section 8) |
| `platform.integration.callbacks.first-delay` | `1m` | callbacks: the first wait after a failed send; it doubles each time (section 9) |
| `platform.integration.callbacks.max-delay` | `6h` | callbacks: the longest wait between two sends |
| `platform.integration.callbacks.give-up-after` | `24h` | callbacks: given up this long after they were created (or resent) |
| `platform.integration.callbacks.keep-finished` | `30d` | callbacks sent or given up are deleted after this |
| `platform.integration.callbacks.accept-v1-signatures` | `false` | callbacks **in**: also accept the earlier `v1` signature, which does not cover the callback ID (section on signed callbacks); only for a sender that cannot sign `v2` yet |
| `platform.integration.worker.enabled` | `true` | the background work: callbacks that are due, and certificate switches at their set time |
| `platform.integration.worker.every` | `15s` | how often the background work runs |
| `platform.integration.scanner.*` | none | the virus scanner for incoming files (section 13) |
| `platform.integration.intake.work-folder` | the system's temporary folder | where polled documents are kept while the app takes them (section 11) |

The limits are defaults: each integration keeps its own copy, which the integration screens can change.

**The outbound settings** (`platform.outbound.*`, module `platform-outbound-spring`) are shared: the test sign-in of a
new login connection (`docs/install/identity.md` section 6) goes through the same installation, proxy and private
certificate authority. Before platform-core 0.3.0 (never released) they were named
`platform.integration.installation`, `.allow-internal-addresses`, `.allow-sign-in-none`, `.private-ca-file` and
`.proxy.*`. The old names now stop the app with `platform.integration.… moved to platform.outbound.…`. Wrong
outbound settings stop the app with `OUTBOUND_SETTINGS_INVALID` and every problem listed.

If an integration setting is wrong, the app does not start. Its log then shows `INTEGRATION_SETTINGS_INVALID` with
every problem at once, for example `platform.outbound.installation is required: ON_PREMISES or HOSTED`.

The integration tables are created only by a schema step, never at startup: a separate Flyway run with location
`classpath:db/platform/integration` and history table `int_schema_history`, run as the schema owner in the app's
database schema (schema version 2: `V1__integration.sql`, `V2__credentials_callbacks.sql`). The app's
database user needs only these rights on them:

| Table | Rights |
|---|---|
| `int_integration`, `int_credential` | `SELECT, INSERT, UPDATE` |
| `int_integration_version`, `int_credential_change` | `SELECT, INSERT` |
| `int_callback_outbox` | `SELECT, INSERT, UPDATE, DELETE` |
| `int_schema_version` | `SELECT` |

If the tables are missing or at another version, the app does not start, with one of the codes `INT_TABLES_MISSING`,
`INT_VERSION_UNKNOWN`, `INT_VERSION_OLDER`, `INT_VERSION_NEWER`, `INT_TABLES_INCOMPLETE`, `INT_PRIVILEGES_MISSING`
or `INT_CHECK_FAILED` (cause and fix in `docs/errors/platform-error-codes.md`). After an upgrade from version 1, run
the schema step once (it adds the version 2 tables) and grant the new rights.

**Logging.** Never set the log level of `org.apache.hc.client5.http.wire` or `org.apache.hc.client5.http.headers` to
`DEBUG`. That is the HTTP library's own trace, and it would write every request header, sign-in included, to the log.
The app's own logs never hold a secret.

### Secret references (P044)

An integration's settings never hold a secret, only a **reference** to it in your secret store (the app's
`SecretResolver`). Every secret an integration names (a password, API key, fixed token or client credential, a
callback's signing secret, a keystore's password) is kept under **its own entity**:

```
int/<entity>/<name>        for example  int/ENT-0001/api-client-secret
```

`<entity>` is the entity's licence slot ID (`ENT-…`) or its entity UUID. A reference under another entity, or under no
entity (`int/api-secret`), is refused when the integration is saved (`INT_CONFIG_SECRET_SCOPE`, naming the field) and
again at every use, before the secret store is asked. So one entity's integration can never name, or send, another
entity's secret. Keep each entity's secrets under its own path in the secret store, and give each entity's admins rights
on that path only. (The outbound proxy's password, `platform.outbound.proxy.password-secret`, belongs to the
installation, not to an integration, and is not under this rule.)

## 2. The outbound proxy

```properties
platform.outbound.proxy.host=proxy.corp.example
platform.outbound.proxy.port=3128
platform.outbound.proxy.bypass=.corp.example,files01
platform.outbound.proxy.user=svc-documents
platform.outbound.proxy.password-secret=integration/proxy-password
```

Every call of an integration that uses the proxy goes through it, including the calls that fetch sign-in tokens. Hosts
on the bypass list are called directly. An integration can also be set not to use the proxy at all.

**When the proxy asks for a user and password (Basic sign-in):** set `proxy.user` and `proxy.password-secret`. The
app sends them with the first request to the proxy, also for `https` calls. No Java start option is needed: the app's
HTTP client sends the proxy sign-in itself.

The proxy password is only sent to the proxy, never to the systems behind it.

## 3. Your private certificate authority

If your internal systems use certificates from your own certificate authority, put the CA certificates in one PEM
file (one or more `-----BEGIN CERTIFICATE-----` blocks). Then set
`platform.outbound.private-ca-file=/etc/documents/private-ca.pem`. They are trusted **in addition to** the
certificates the system already trusts; nothing is replaced. There is no "trust everything" switch. The file is read
at startup: restart the app after you change it.

## 4. Internal addresses on premises, and what the address check does

Each call's address is checked right before the call is made. The check looks at every address the name resolves to.
A call is refused (`INT_ADDRESS_BLOCKED`) if any of them is:

- a cloud metadata or link-local address: **always** refused, whatever the settings;
- a loopback or private address (`10.x`, `172.16–31.x`, `192.168.x`, `127.x`, `fc00::/7`): refused unless
  `allow-internal-addresses=true`. That setting is on premises only, and never on the hosted service.

**Pinning.** The connection goes to the address that was checked, not to a new lookup of the name. A name server that
gives a safe address to the check and an internal or metadata address a moment later (DNS rebinding) is not followed.
The certificate is still checked against the name in the address, and the name is still sent to the server (SNI), so
certificates work as before. Pinning applies to every call: API calls, token requests, health checks, the login
connection test and callbacks. When a name has several addresses, the first one that passed the check is used. Through
the proxy, the app connects to the proxy, and the proxy looks up the name; the check of the name is made before.

Plain `http` is allowed only where internal addresses are. Redirects are never followed.

## 5. Profile files

An API's owner can publish a **profile** for their API: a file in the format `platform-integration-profile/1` that
describes the API's calls, answers and health check. It never holds an address, a secret or customer data. The app
ships no profiles. When you receive one:

1. Keep the file unchanged and note its release tag.
2. Put it in the profile folder (`platform.integration.profile-folder`), for example
   `/etc/documents/integration-profiles/example-api-v1.profile.json`. The folder must be readable by the app's
   service account. Only `.json` files directly in that folder are used.
3. Name it in the setup sign-in file under the product's `integrationProfiles`. Setup checks the file and its tag
   before anything is written (`docs/install/setup.md` section 3b). It reports `SETUP_PROFILE_INVALID` or
   `SETUP_PROFILE_TAG_MISSING`, with the place in the file.

The integration screens read the file each time IT sets up an integration from it, so a replaced file is used without
a restart. A file with problems is refused with `INT_PROFILE_*` codes (section 14).

## 6. Folder export: the share and the service account

A folder export writes one folder per package into the target folder, for example
`\\fileserver\exports\in\PKG-2026-0042\`. The folder holds the documents and a `manifest.json`, which lists every
document with its size and SHA-256 checksum. Your systems should read only folders **without** the `.partial` ending:

- The app first writes into `<package>.partial\`. It writes each document and checks its checksum on the disk.
- It writes `manifest.json` last.
- Then it renames the folder to its final name. A final folder always has its manifest.
- If anything fails, only the `.partial` folder is touched, and it is removed when possible. The next attempt
  removes a leftover `.partial` folder of the same package.

**Paths.** On Windows, use the share path as it is (`\\fileserver\exports\in`) or a local path (`D:\exports`). On
Linux, mount the share (for example at `/mnt/exports`) and configure the mount path. The path must be absolute.

**Rights for the app's service account** on the target folder: list the folder, create folders, create and write
files, read them back, rename files and folders, and delete files and folders. Delete is needed to remove its own
`.partial` folders, the health probe and the Test folder. On a Windows share, give the account **Modify** on the folder,
in both the share permissions and the NTFS permissions. The account needs no rights above the target folder.

The health check writes and removes one small file, `.platform-health-<random>.tmp`. The Test action writes a fake
one-page PDF and a manifest into `platform-test-<date and time>` and removes it again. A package folder of the same
name that is already there is never overwritten (`INT_FOLDER_EXISTS_DIFFERENT`).

## 7. Health checks

With Spring Boot Actuator, `/actuator/health/integrations` has one entry per integration that is switched on, named
by its ID (sources too: a polled API, a watched folder), and a `scanner` entry when a virus scanner is set. Each entry
shows only the `outcome` and the `code`. An integration that is up or busy is `UP`; any other
result is `DOWN`. A result is kept for `health.cache-for`.

A system that is down makes this entry `DOWN`. If your orchestrator restarts the app or stops sending it traffic on
health (liveness and readiness probes), keep `integrations` out of those groups. This is Spring Boot's default when
probes are on. If you list health contributors yourself, do not add it to
`management.endpoint.health.group.liveness.include`.

**With platform-monitoring-spring** in the app, there is no `integrations` entry. Each switched-on integration is
then the check `integration-<id>` in `/actuator/health/dependencies` (working, having problems when busy, or down),
which never counts for readiness, and the Support screen lists it (`docs/install/monitoring.md`). Callbacks have no
check there, and neither has the virus scanner.

## 8. Client certificates (mutual TLS)

Some systems accept a call only when the app shows a **client certificate**: sign-in type `MUTUAL_TLS`, alone or
together with another sign-in type (for example with OAuth 2.0; the token request can show the same certificate). Each
integration has its own certificate. There are two ways to give it.

**Upload it** in the integration's certificate screen. Use a PKCS#12 file (`.p12`, `.pfx`, with its password) or a PEM
file. A PEM file holds the certificate (`-----BEGIN CERTIFICATE-----`, intermediates after it) and the private key
without a password (`-----BEGIN PRIVATE KEY-----`). The app checks that the key belongs to the certificate and that the
certificate has not expired. It then stores both **encrypted with your customer key** in the key service. The file and
the password are not kept.

**Or point to a keystore** that you manage: the file's full path, the alias of the entry, and where its password is
kept in the secret store (a reference). Nothing is copied. The app reads the file when a call needs the certificate,
so you can also renew the certificate in the file itself. The app then shows the new one and records the change. The
app's service account must be able to read the file.

The screen shows only the certificate's subject, issuer, serial number, SHA-256 fingerprint and validity dates. The
private key and the file are never shown, logged or returned.

**Renewing without downtime.** An integration can hold two certificates, CURRENT and NEXT:

1. Upload (or point to) the new certificate. It becomes NEXT; the calls still use CURRENT.
2. Give the system's owner the new certificate's fingerprint, so they trust it.
3. Run the integration's **Test** with NEXT. It sends only fake data and shows the certificate.
4. Switch: press *Switch to next*, or set a time at which NEXT becomes CURRENT by itself (the background work does
   it, section 1). The old certificate is kept as RETIRED for the history, without its key.

To cancel, *Discard next*. Each step is kept in the integration's history with who and when.

**Expiry warnings.** The app warns IT 30, 7 and 1 days before a certificate in use ends, and again once it has ended.
It uses its own notices; the days can be changed with `credentials.expiry-warning-days`. A CURRENT certificate that
has expired stops every call of the integration (`INT_CREDENTIAL_EXPIRED`) until you switch to a valid one.

## 9. Callbacks (telling your system that a case is done)

A **callback** integration sends a short JSON message to an address of yours when a case is done or failed. It holds
the case's identifiers and state, never a document. The same address checks apply as for every call (section 4).
Each message is **signed** with a secret that only the app and your receiver know. Put the secret in the secret store
and give the integration its reference. The receiver may also want a sign-in on top (for example a client
certificate, section 8).

A message that cannot be delivered is sent again: after 1 minute, then 2, 4, 8 and so on, at most 6 hours apart, for
24 hours (section 1). A `Retry-After` from your receiver is followed. A message your receiver refuses (a 4xx answer
other than sign-in) is not sent again. Messages that were given up are listed for the app's IT screen, which can send
them again.

**What your receiver checks**, for every message, in plain steps (signature version 2, P044):

1. Read the headers `Platform-Signature` and `Platform-Callback-Id`. The signature looks like
   `t=1700000000,v2=2039c932…`. `t` is the time it was sent (Unix seconds) and `v2` is the signature.
2. Take the message body exactly as it arrived, byte for byte (do not parse it and write it again).
3. Compute HMAC-SHA256 with the signing secret as the key over: the value of `t`, a full stop `.`, the value of
   `Platform-Callback-Id`, a full stop `.`, then the body. Write the result as lower-case hex.
4. Compare it with `v2` using a constant-time comparison. If they differ, refuse the message (401).
5. Check that `t` is within 5 minutes of your clock. If not, refuse it: it is old or replayed.
6. If you have seen this `Platform-Callback-Id` before, answer 200 and do nothing else: it is a repeat (the app sends
   the same ID again when it did not get your answer). Because the ID is signed, a copied message cannot be sent
   again under a new ID.
7. Answer `2xx` quickly, then do the work.

Example: secret `whsec-test-1`, `t=1700000000`, `Platform-Callback-Id: cb-example-1`, body
`{"case":"C-1","state":"DONE"}`. The signed text is `1700000000.cb-example-1.{"case":"C-1","state":"DONE"}`, and `v2`
is `2039c9328fff815a89ac1cfdf5f70a4410f2d733fda64ef106f7f2e8f00ed2e1`
(`printf '%s' '1700000000.cb-example-1.{"case":"C-1","state":"DONE"}' | openssl dgst -sha256 -hmac 'whsec-test-1'`).

**Version 1 (before 0.7).** The app sent `v1`, the HMAC over `t`, `.` and the body only, without the ID; a copied
message could then be sent again under a new ID. The app sends `v2` only now. A receiver built on the platform's
`CallbackVerifier` accepts `v1` only when its installation sets
`platform.integration.callbacks.accept-v1-signatures=true` (off by default); turn it on only for a sender that cannot
sign `v2` yet, and off again once it can.

**Changing the secret** without losing messages:

1. Make your receiver accept the old **and** the new secret (step 4 above: either may match).
2. Put the new secret in the secret store (under the same reference, or a new reference set in the integration). From
   the next message on, the app signs with it.
3. When no message signed with the old secret is waiting any more (after the 24-hour window at most), remove the old
   secret from the receiver.

An app that **receives** signed callbacks from another system checks them the same way (`CallbackVerifier`).

## 10. "Accepted, check later"

Some systems answer a delivery with *202 Accepted* and do the work later. The integration then says how to ask for
the status, and the app asks again on its own schedule. It asks at the time the system gives in `Retry-After`, or
after the configured wait. A status of done counts as delivered. A status of failed is shown with the system's own
code (`INT_STATUS_FAILED`). Nothing is needed from IT for this beyond the integration's settings.

## 11. Polling an API (intake)

An integration can also **fetch** documents: it asks a system's API for the documents waiting there, downloads each
one and, once the app has stored it, tells the system it was received (the "acknowledge"). The app runs one round
(a "pass") at the times it sets; each pass reads at most the configured number of pages.

- The calls use everything sections 2 to 5 and 8 describe: the proxy, your CA, the address check, sign-in and client
  certificates. The integration screens (or a profile file from the API's owner) set the list call, how the next
  page is asked for, the download call and the acknowledge call.
- Each document is streamed to a temporary file while the app takes it, and removed right after. By default that is
  the system's temporary folder. To use another one, set `platform.integration.intake.work-folder` to a folder only
  the app's service account can read, with room for the largest document.
- A document is acknowledged **only after the app has it**. If anything fails part of the way (the system is busy or
  down, the app's own store is down), the app starts the next pass after the last page it finished. Documents it
  already has are recognised and not taken twice.
- A document the system refuses to hand over, or one over the size limit, is shown in the app with its code and left
  where it is. It is not acknowledged.

## 12. Setting up a watched folder or share (intake)

The app watches a folder into which your systems (a scanner station, a document system, a file transfer job) put
**drops**, one drop for each set of documents that belong together:

- **one subfolder per drop**: `\fileserver\inbox\LOAN-17\` with the documents and the trigger file in it; or
- **files grouped by name** in the folder itself: `LOAN-17.pdf`, `LOAN-17.p2.pdf` and `LOAN-17.json` are one drop,
  `LOAN-17` (the name up to its first dot).

**The trigger file must be written last.** It is the sign that the drop is complete: a JSON or XML file listing the
drop (`manifest.json`, `<name>.xml`), or an empty marker file (`<name>.done`, `ready.lck`). Your process must:

1. write every document of the drop completely, and close it;
2. then write the trigger file.

Even with the trigger there, the app waits until every file has stopped changing (sizes and times the same for the
configured time, 30 seconds by default). A drop that has files but no trigger after the waiting time (60 minutes by
default) is reported once in the app as incomplete (`INT_WATCH_INCOMPLETE`). It is not moved: check whether your
process stopped before it wrote the trigger.

**What the app does with a drop.** It never changes a file. It moves the drop between four folders it keeps in the
watched folder (it creates them the first time):

| Folder | What is in it |
|---|---|
| `_processing\<drop>-<claim>` | a drop the app is taking right now. Several app instances can watch the same folder: the first one to move a drop here takes it, and the others leave it alone. If an instance stops in the middle, the drop goes back to the watched folder after the claim time (30 minutes by default) and is taken again |
| `_archive\<drop>-<claim>` | a drop the app has taken. Delete old ones by your own retention rules |
| `_problem\<drop>-<claim>` | a drop the app could not take, with a small `problem.json`: the code, the time and, when the app refused it, the app's own code. It never holds a document's content. Fix the cause, then move the drop back into the watched folder |
| `_quarantine\<drop>-<claim>` | a drop with a file the virus scanner found infected (section 13), with `problem.json` naming the file and what was found. Nothing in it was processed |

Names starting with `_` or `.` are never taken as drops. Links (symbolic links, Windows junctions) are never followed;
a drop that holds one goes to `_problem`.

**Paths.** As for folder export (section 6): on Windows the share path as it is, or a local path; on Linux the mount
path. The path must be absolute. Watched folders are not available on the hosted service.

**Rights for the app's service account** on the watched folder: list it, read files, create folders, create and write
files (`problem.json`, the claim marker and the health probe), rename files and folders, and delete files and folders.
On a Windows share, give the account **Modify**, in both the share permissions and the NTFS permissions. The health
check writes and removes one small file in `_processing`.

**Trigger files.** For a JSON or XML trigger the integration can name values to read from it, by fixed paths (for
example `/loan/number` in JSON, `/drop/loan/@number` in XML). The app decides what they mean. XML triggers must not
have a DTD (`<!DOCTYPE`); entities, external files and includes are refused. A trigger that cannot be read
(malformed, too large, too deeply nested) sends the drop to `_problem` with `INT_WATCH_TRIGGER_UNREADABLE`. When the
trigger lists the drop's files, the app is told about files that are not on the list and names on the list without a
file; what that means is the app's decision.

## 13. The virus scanner (optional)

When your installation has a virus scanner, the app can use it for every incoming file, from polling and from a
watched folder: each file is scanned **before** the app processes it. Without one, scanning is off.

| Property | Default | |
|---|---|---|
| `platform.integration.scanner.type` | `NONE` | `CLAMD` (a ClamAV daemon, `INSTREAM`) or `ICAP` (an ICAP server, `RESPMOD`) |
| `platform.integration.scanner.host` | — | the scanner's host name or address (an internal address needs `allow-internal-addresses`) |
| `platform.integration.scanner.port` | `3310` / `1344` | clamd's or the ICAP server's port |
| `platform.integration.scanner.icap-service` | `avscan` | ICAP only: the service name on the server (for example `srv_clamav`, `avscan`) |
| `platform.integration.scanner.timeout` | `60s` | to connect and to get each answer: 1 second to 10 minutes |

- **Infected**: the file is never processed. A polled document is not acknowledged; a drop goes to `_quarantine`.
  The app raises an alert for IT.
- **The scanner cannot scan** (down, too slow, an error): the file is held and scanned again at the next pass. It is
  never let through unscanned. Check the scanner; nothing else is needed.
- **Size.** clamd refuses streams over its `StreamMaxLength` (25 MB by default). Set it at least as high as the
  integration's largest file, or the file is held with `INT_SCAN_FAILED`.
- The connection is plain TCP inside your network. The scanner gets the file's content and nothing else.
- `/actuator/health/integrations` has a `scanner` entry when a scanner is set (clamd `PING`, ICAP `OPTIONS`); not
  when the app has platform-monitoring-spring (section 7).

To test the set-up, put the standard EICAR test file (from eicar.org) into a test drop: it must end up in
`_quarantine` with `INT_SCAN_INFECTED`.

## 14. Codes you can see, and what to do

Every code, with its message, cause and what to do, is also in the generated catalogue
`docs/errors/platform-error-codes.md`. The app's Support screen (`docs/install/monitoring.md`) shows each
integration's state with its code.

The app's screens, audit and logs show these codes. They never show a password, a token, a document's content or a
value.

**Proxy and network**

| Code | What happened | What to do |
|---|---|---|
| `INT_PROXY_SIGN_IN_REFUSED` | The proxy refused the user or password (407), or asks for sign-in that is not set | Check `proxy.user` and the password in the secret store |
| `INT_PROXY_REFUSED` | The proxy refused the connection | Allow the destination on the proxy, or add it to the bypass list |
| `INT_CANT_CONNECT`, `INT_CONNECT_TIMEOUT` | No connection to the destination or proxy | Check the address, the firewall and that the system is up |
| `INT_HOST_UNKNOWN` | The name is not known to the name servers | Check the address and DNS |
| `INT_TIMEOUT` | No answer in time, or a file took too long to write | The system may be slow; raise the integration's read timeout if needed |
| `INT_CONNECTION_LOST` | The connection broke before the answer | Usually passes; if it repeats, check the network and the proxy |
| `INT_TLS_FAILED` | The certificate is not trusted or does not match the name | Add your CA (section 3), or use the name in the certificate |
| `INT_ADDRESS_BLOCKED` | The address is a metadata, link-local or (when not allowed) internal address | Section 4 |
| `INT_ADDRESS_NOT_SECURE` | Plain `http` where it is not allowed | Use `https` |

**Secrets and sign-in**

| Code | What happened | What to do |
|---|---|---|
| `INT_SECRET_NOT_FOUND` | Nothing is kept under the reference | Put the secret in the secret store under that name |
| `INT_SECRET_UNAVAILABLE` | The secret store did not answer | Check the secret store; the next attempt tries again |
| `INT_SIGN_IN_REFUSED` | The destination refused the sign-in (401 / 403) | Check the credential with the destination's owner |
| `INT_SIGN_IN_NONE_NOT_ALLOWED` | An integration without sign-in where it is not allowed | Set up a sign-in, or `allow-sign-in-none` on premises |
| `INT_TOKEN_UNREACHABLE` | The token address could not be reached or was failing | Check the token address and the network |
| `INT_TOKEN_REFUSED` | The token address refused the request (scope, audience) | Check scope and audience with the token issuer |
| `INT_TOKEN_CREDENTIAL_REFUSED`, `INT_TOKEN_CLIENT_UNKNOWN` | The client ID or its secret or certificate was refused | Check the client registration |
| `INT_TOKEN_CREDENTIAL_INVALID`, `INT_TOKEN_CREDENTIAL_EXPIRED` | The certificate credential is not usable, or expired | Replace it in the secret store |
| `INT_TOKEN_UNEXPECTED_REPLY`, `INT_TOKEN_CLAIMS_MISSING` | The token is not what the integration needs | Check the token issuer's settings |

**Client certificates** (section 8)

| Code | What happened | What to do |
|---|---|---|
| `INT_CREDENTIAL_FILE_INVALID` | The file is not a PKCS#12 or PEM file with one certificate and its private key (or it is over 32 KB) | Export the certificate together with its private key, then upload again |
| `INT_CREDENTIAL_PASSWORD_WRONG` | The file's or keystore's password is wrong, or the PEM key has a password | Check the password; for PEM, export the key without a password |
| `INT_CREDENTIAL_KEY_MISMATCH` | The private key does not belong to the certificate | Upload the certificate with its own key |
| `INT_CREDENTIAL_EXPIRED` | The certificate has expired (refused at upload; a CURRENT one stops every call) | Add the renewed one as NEXT and switch |
| `INT_CREDENTIAL_NOT_YET_VALID` | The certificate's start date is in the future | Keep it as NEXT and switch once it is valid |
| `INT_CREDENTIAL_MISSING` | The integration signs in with a certificate, but none is set (or no NEXT for a Test of next) | Upload one, or point to a keystore |
| `INT_CREDENTIAL_KEY_SERVICE_FAILED` | The key service could not encrypt or decrypt the stored certificate | Check the key service (its health); the next attempt tries again |
| `INT_CREDENTIAL_REFUSED` | The system refused the certificate during the TLS handshake | Give its owner the certificate's fingerprint and check that they trust it and it is not revoked |
| `INT_CREDENTIAL_KEYSTORE_UNREADABLE` | The keystore file is missing, not readable, or not a keystore | Check the path and the service account's read right |
| `INT_CREDENTIAL_ALIAS_NOT_FOUND` | The keystore has no key entry under that alias | Check the alias (`keytool -list -keystore <file>`) |
| `INT_CREDENTIAL_NEXT_EXISTS`, `INT_CREDENTIAL_NO_NEXT` | There is already a NEXT; or there is none to switch to or discard | Switch to or discard NEXT first; or add one |
| `INT_CREDENTIAL_CONFLICT` | Someone else changed the certificates at the same time | Reload the screen and try again |
| `INT_CONFIG_MUTUAL_TLS_INVALID` | A certificate over plain `http`, or with sign-in NONE | Use `https`; choose "certificate alone" instead of NONE |

**Callbacks** (section 9)

| Code | What happened | What to do |
|---|---|---|
| `INT_CALLBACK_SIGNATURE` | A callback received with a missing, malformed or wrong signature | Check that both sides use the same secret; during a change, accept both |
| `INT_CALLBACK_EXPIRED` | A callback received more than 5 minutes from its time | Check both clocks (NTP); an old message may be a replay |
| `INT_CALLBACK_REPLAYED` | A callback received whose ID was already accepted | Nothing: a repeat is ignored |
| `INT_CALLBACK_BODY_INVALID` | The app tried to send a body that is not one JSON object, or over 64 KB | An app fault: check the app's log |
| `INT_CALLBACK_NOT_FOUND` | No given-up callback with that ID to send again | Reload the list |
| `INT_CONFIG_CALLBACK_INVALID` | A callback without a signing secret | Put the secret in the secret store and set its reference |
| `INT_CONFIG_SECRET_SCOPE` | A secret reference is not under the integration's own entity (`int/<entity>/<name>`); nothing was asked of the secret store | Section 1, "Secret references": keep the secret under the entity, for example `int/ENT-0001/api-client-secret`, and name that |

**Accepted, check later** (section 10)

| Code | What happened | What to do |
|---|---|---|
| `INT_ACCEPTED` | The system accepted the delivery and works on it later | Nothing: the app asks for the status |
| `INT_STATUS_FAILED` | The system says the work failed; its own code is shown | Look up the system's code with its owner |
| `INT_STATUS_REFERENCE_MISSING`, `INT_STATUS_UNKNOWN` | The 202 answer had no status reference, or a status the integration does not know | Check with the system's owner whether the API changed |
| `INT_CONFIG_ACCEPTED_INVALID` | The "check later" settings are not valid (not on the last step, no done or failed values) | Correct the integration's settings; the screen names the field |

**Folder export**

| Code | What happened | What to do |
|---|---|---|
| `INT_FOLDER_NOT_FOUND` | The target folder does not exist (or the path is not a path on this system) | Create it, or correct the path; on Linux mount the share |
| `INT_FOLDER_NO_PERMISSION` | The service account may not write there | Section 6: rights for the service account |
| `INT_FOLDER_LINK_REFUSED` | A link (symbolic link or junction) stands where the app writes or removes its own package folder; nothing behind it was touched | Remove the link and find out who made it; only the service account may create entries in the export folder (section 6) |
| `INT_FOLDER_FULL` | The disk or the account's quota is full | Free space or raise the quota |
| `INT_FOLDER_UNREACHABLE` | The share cannot be reached | Check the file server, the share and the network |
| `INT_FOLDER_EXISTS_DIFFERENT` | A package folder of that name is already there with other content | Move the old folder away, or check the folder name setting |
| `INT_FOLDER_WRITE_FAILED` | Writing failed for another reason | Check the file server's logs; the next attempt starts over |
| `INT_INTEGRITY_MISMATCH` | A file read back differs from what was sent (or the destination reports another checksum) | Check the disk or share; nothing final was written |

**Polling an API** (section 11)

| Code | What happened | What to do |
|---|---|---|
| `INT_POLL_ITEMS_MISSING` | The list answer has no list of items where the integration expects it | Check with the system's owner whether the API changed |
| `INT_POLL_ITEM_ID_MISSING` | An item without a usable ID; it is left where it is | Check the item ID setting against the API |
| `INT_POLL_NEXT_PAGE_INVALID` | The next page points to another server, or its cursor is not a value | Check the paging settings against the API |
| `INT_POLL_STORAGE_FAILED` | A downloaded document could not be kept in the work folder | Check `intake.work-folder`: free space and the service account's rights |
| `INT_POLL_HANDLER_FAILED` | The app could not store a document (its own problem); nothing was acknowledged | Check the app's log; the next pass tries again |
| `INT_FILE_TOO_LARGE` | A document is over the integration's largest file; it is left where it is | Raise the limit, or ask the sender for smaller files |

**Watched folder or share** (section 12)

| Code | What happened | What to do |
|---|---|---|
| `INT_WATCH_ROOT_NOT_FOUND` | The watched folder does not exist | Create it, or correct the path; on Linux mount the share |
| `INT_WATCH_NO_PERMISSION` | The service account may not work in the watched folder | Section 12: rights for the service account |
| `INT_WATCH_LINK_REFUSED` | A link (symbolic link or junction) stands in place of `_processing`, `_archive`, `_problem`, `_quarantine` or a claimed drop; nothing behind it was touched | Remove the link and find out who made it; senders may create drops only (section 12) |
| `INT_WATCH_SHARE_UNREACHABLE` | The share cannot be reached (also in the middle of a round) | Check the file server, the share and the network; claimed drops go back by themselves after the claim time |
| `INT_WATCH_TRIGGER_UNREADABLE` | The trigger file is malformed, too large, too deep, or XML with a DTD; the drop is in `_problem` | Fix the trigger file, then move the drop back |
| `INT_WATCH_DROP_INVALID` | A drop with a link or a folder in it, too many files, a file too large, or two trigger files; in `_problem` | Fix the drop, then move it back |
| `INT_WATCH_FILE_CHANGED` | A file changed after the app took the drop; in `_problem` (also when the bytes the virus scanner read differ from the ones checked) | Your process must finish every file before it writes the trigger |
| `INT_WATCH_INCOMPLETE` | Files without a trigger after the waiting time; nothing was moved | Check whether your process stopped before writing the trigger |
| `INT_WATCH_MOVE_FAILED` | A drop could not be moved inside the watched folder | Check the rename and delete rights, and that no program holds the files open |
| `INT_WATCH_REFUSED` | The app refused the drop; `problem.json` has the app's own code | Look up the app's code; fix the drop and move it back |
| `INT_WATCH_HANDLER_FAILED` | The app could not store the drop (its own problem); the drop was put back | Check the app's log; the next round tries again |

**Virus scanner** (section 13)

| Code | What happened | What to do |
|---|---|---|
| `INT_SCAN_INFECTED` | The scanner found a virus; the file was quarantined, not processed | Tell the sender; delete the quarantined copy after checking it |
| `INT_SCAN_FAILED` | The scanner answered with an error (for example a file over its stream limit); the file is held | Check the scanner's log and its size limit |
| `INT_SCAN_UNREACHABLE` | The scanner cannot be reached or did not answer in time; files are held | Check the scanner's host, port and firewall; raise `scanner.timeout` for large files |

**The destination's answer**

| Code | What happened | What to do |
|---|---|---|
| `INT_SENT`, `INT_ALREADY_RECEIVED`, `INT_HEALTHY`, `INT_TEST_CHECKED` | It worked | — |
| `INT_REFUSED` | The destination refused the request with its own code | Look up the destination's code with its owner |
| `INT_ADDRESS_NOT_FOUND` | 404: the address or path is wrong | Check the integration's address |
| `INT_REDIRECT_REFUSED` | The destination redirected; redirects are never followed | Use the final address |
| `INT_BUSY`, `INT_DESTINATION_BUSY` | The destination is busy | Nothing: the app waits and sends less |
| `INT_OUTAGE` | The destination is failing | The app pauses and checks its health |
| `INT_UNCLEAR_ANSWER`, `INT_RESPONSE_TOO_LARGE`, `INT_UNEXPECTED_STATUS` | An answer the configuration does not expect | Check with the destination's owner whether the API changed |

**Configuration and data** (shown when an integration is saved or run)

| Code | What to do |
|---|---|
| `INT_DISABLED` | The integration is switched off |
| `INT_KIND_NOT_SUPPORTED` | This kind of integration is not available in this release |
| `INT_CONFIG_INVALID`, `INT_CONFIG_*` | The configuration has a problem; the screen names the field |
| `INT_CONFIG_FOLDER_INVALID` | The folder path is not absolute, or a folder or file name setting is not one valid name |
| `INT_CONFIG_FOLDER_HOSTED` | Folder export and watched folders are not available on the hosted service |
| `INT_CONFIG_POLL_INVALID` | The polling settings are not valid (calls, pointers, paging); the screen names the field |
| `INT_CONFIG_WATCH_INVALID` | The watched folder settings are not valid (path, trigger name, times, value paths); the screen names the field |
| `INT_CONFIG_OMIT_PLACE` | "Leave out when missing" (`{name?}`) is used anywhere but as a whole member of a JSON body |
| `INT_VALUE_MISSING`, `INT_VALUE_NOT_ALLOWED` | A value the integration needs is missing, or cannot be used there (for example a name Windows does not allow) |
| `INT_FILE_TOO_LARGE`, `INT_TOO_MANY_DOCUMENTS`, `INT_PACKAGE_TOO_LARGE` | Over the integration's limits |
| `INT_SENSITIVE_FIELD_NOT_ALLOWED` | A sensitive case field would be sent where the integration does not allow it (never in a folder or file name) |
| `INT_FIELD_VALUES_INVALID`, `INT_CONTENT_UNREADABLE` | The app could not give the case values or a document; check the app's own log |
| `INT_PROFILE_*` | A profile file has a problem; the message names the place in the file (section 5) |
