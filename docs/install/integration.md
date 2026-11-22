# Integrations — install guide for IT

The app sends documents to your other systems through integrations. It sends them to an API (API push), or it
writes them into a folder or network share with a manifest file (folder export). Each integration is set up in the
app's integration screens, and every change is kept as a new version. This page lists what the installation itself
needs: the settings below (module `platform-integration-spring`), the outbound proxy, your private certificate
authority, the profile files, and the rights on an export share. It ends with every code you can see, and what to do
about it.

Secrets never go into these settings or into an integration's configuration. Only a **reference** goes there: the
name under which the secret is kept in your secret store, for example `integration/proxy-password`. The app reads the
secret at the moment it is needed, so a changed password works without a restart.

## 1. App settings (`platform.integration.*`)

| Property | Default | |
|---|---|---|
| `platform.integration.enabled` | `true` | `false` switches integrations off |
| `platform.integration.installation` | — | **required**: `ON_PREMISES` (your own systems) or `HOSTED` (our hosted service) |
| `platform.integration.allow-internal-addresses` | `false` | on premises only: the app may call addresses on your internal network (loopback and private ranges) and plain `http` there |
| `platform.integration.allow-sign-in-none` | `false` | on premises only: an integration may call without signing in (warned and audited) |
| `platform.integration.private-ca-file` | none | your private certificate authorities, one PEM file (section 3) |
| `platform.integration.proxy.host`, `.port` | none | your outbound proxy (section 2) |
| `platform.integration.proxy.bypass` | none | hosts that do not use the proxy, comma-separated: an exact name (`files01`) or a suffix (`.corp.example`) |
| `platform.integration.proxy.user` | none | the proxy's sign-in user, when the proxy asks for one |
| `platform.integration.proxy.password-secret` | none | where the proxy password is kept in the secret store (a reference, never the password) |
| `platform.integration.limits.connect-timeout` | `10s` | default for new integrations: 1 second to 2 minutes |
| `platform.integration.limits.read-timeout` | `120s` | default: 1 second to 30 minutes. For a folder export, this is the time allowed for writing one file |
| `platform.integration.limits.max-file-size` | `250MB` | default: the largest document |
| `platform.integration.limits.max-documents` | `100` | default: the most documents in one package |
| `platform.integration.limits.max-response-size` | `1MB` | default: the largest answer read from an API |
| `platform.integration.limits.max-package-size` | `2GB` | default: the largest package |
| `platform.integration.profile-folder` | none | the folder where you put profile files (section 5) |
| `platform.integration.health.enabled` | `true` | one health check per integration (section 7) |
| `platform.integration.health.cache-for` | `60s` | how long one health result is kept: 5 seconds to 1 hour |

The limits are defaults: each integration keeps its own copy, which the integration screens can change.

If a setting is wrong, the app does not start. Its log then shows `INTEGRATION_SETTINGS_INVALID` with every problem
at once, for example `platform.integration.installation is required: ON_PREMISES or HOSTED`.

The app's database user needs only these rights on the integration tables (created by the app's schema step, never
at startup): `SELECT, INSERT, UPDATE` on `int_integration`, `SELECT, INSERT` on `int_integration_version`, and
`SELECT` on `int_schema_version`. If the tables are missing or at another version, the app does not start.

## 2. The outbound proxy

```properties
platform.integration.proxy.host=proxy.corp.example
platform.integration.proxy.port=3128
platform.integration.proxy.bypass=.corp.example,files01
platform.integration.proxy.user=svc-documents
platform.integration.proxy.password-secret=integration/proxy-password
```

Every call of an integration that uses the proxy goes through it, including the calls that fetch sign-in tokens. Hosts
on the bypass list are called directly. An integration can also be set not to use the proxy at all.

**When the proxy asks for a user and password (Basic sign-in):** set `proxy.user` and `proxy.password-secret`, and
**start the app with this Java option**:

```
-Djdk.http.auth.tunneling.disabledSchemes=
```

The option ends with `=` and has nothing after it. Java's own default stops Basic sign-in from being sent to a proxy
for `https` calls. The app never changes Java settings by itself. Without the option, every `https` call through the
proxy stops before it connects, with `INT_PROXY_SIGN_IN_DISABLED_BY_JVM`, and the app logs a warning at startup.
Where to put the option: in the service's start command, or in `JAVA_TOOL_OPTIONS` for the service account.

The proxy password is only sent to the proxy, never to the systems behind it.

## 3. Your private certificate authority

If your internal systems use certificates from your own certificate authority, put the CA certificates in one PEM
file (one or more `-----BEGIN CERTIFICATE-----` blocks). Then set
`platform.integration.private-ca-file=/etc/documents/private-ca.pem`. They are trusted **in addition to** the
certificates the system already trusts; nothing is replaced. There is no "trust everything" switch. The file is read
at startup: restart the app after you change it.

## 4. Internal addresses on premises, and what the address check does

Each call's address is checked right before the call is made. The check looks at every address the name resolves to.
A call is refused (`INT_ADDRESS_BLOCKED`) if any of them is:

- a cloud metadata or link-local address: **always** refused, whatever the settings;
- a loopback or private address (`10.x`, `172.16–31.x`, `192.168.x`, `127.x`, `fc00::/7`): refused unless
  `allow-internal-addresses=true`. That setting is on premises only, and never on the hosted service.

What it does not guarantee yet: the connection is not pinned to the address that was checked. A name server that
gives a different address between the check and the connection (DNS rebinding) is not stopped by this check alone.
Pinning comes with mutual TLS (the next integration release), and the hosted service does not go live without it. On
premises, use name servers you control.

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
a restart. A file with problems is refused with `INT_PROFILE_*` codes (section 8).

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
by its ID. Each entry shows only the `outcome` and the `code`. An integration that is up or busy is `UP`; any other
result is `DOWN`. A result is kept for `health.cache-for`.

A system that is down makes this entry `DOWN`. If your orchestrator restarts the app or stops sending it traffic on
health (liveness and readiness probes), keep `integrations` out of those groups. This is Spring Boot's default when
probes are on. If you list health contributors yourself, do not add it to
`management.endpoint.health.group.liveness.include`.

## 8. Codes you can see, and what to do

The app's screens, audit and logs show these codes. They never show a password, a token, a document's content or a
value.

**Proxy and network**

| Code | What happened | What to do |
|---|---|---|
| `INT_PROXY_SIGN_IN_DISABLED_BY_JVM` | The proxy needs Basic sign-in, but Java does not send it for https | Start the app with `-Djdk.http.auth.tunneling.disabledSchemes=` (section 2) |
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

**Folder export**

| Code | What happened | What to do |
|---|---|---|
| `INT_FOLDER_NOT_FOUND` | The target folder does not exist (or the path is not a path on this system) | Create it, or correct the path; on Linux mount the share |
| `INT_FOLDER_NO_PERMISSION` | The service account may not write there | Section 6: rights for the service account |
| `INT_FOLDER_FULL` | The disk or the account's quota is full | Free space or raise the quota |
| `INT_FOLDER_UNREACHABLE` | The share cannot be reached | Check the file server, the share and the network |
| `INT_FOLDER_EXISTS_DIFFERENT` | A package folder of that name is already there with other content | Move the old folder away, or check the folder name setting |
| `INT_FOLDER_WRITE_FAILED` | Writing failed for another reason | Check the file server's logs; the next attempt starts over |
| `INT_INTEGRITY_MISMATCH` | A file read back differs from what was sent (or the destination reports another checksum) | Check the disk or share; nothing final was written |

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
| `INT_CONFIG_FOLDER_HOSTED` | Folder export is not available on the hosted service |
| `INT_CONFIG_OMIT_PLACE` | "Leave out when missing" (`{name?}`) is used anywhere but as a whole member of a JSON body |
| `INT_VALUE_MISSING`, `INT_VALUE_NOT_ALLOWED` | A value the integration needs is missing, or cannot be used there (for example a name Windows does not allow) |
| `INT_FILE_TOO_LARGE`, `INT_TOO_MANY_DOCUMENTS`, `INT_PACKAGE_TOO_LARGE` | Over the integration's limits |
| `INT_SENSITIVE_FIELD_NOT_ALLOWED` | A sensitive case field would be sent where the integration does not allow it (never in a folder or file name) |
| `INT_FIELD_VALUES_INVALID`, `INT_CONTENT_UNREADABLE` | The app could not give the case values or a document; check the app's own log |
| `INT_PROFILE_*` | A profile file has a problem; the message names the place in the file (section 5) |
