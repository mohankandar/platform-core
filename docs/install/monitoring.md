# Monitoring — install guide for IT

The app checks the things it depends on (its database, the key service, your login system, the licence, mail,
folders, other services, the integrations) on its own, all the time. This page says what each check means, why the
app may refuse to start, where IT sees the list, how to send support a diagnostic bundle, and which health addresses
to give your probes.

Every problem has a **code**, the **exact cause** and **what to do**. People using the app only see a plain message
and a **reference** such as `R-0J5K2Q7M9X4D`; the same reference is in the log line and on the Support screen. The
full list of codes, with what to do for each: `docs/errors/platform-error-codes.md`.

## 1. What is checked

| Check | It looks at | Can stop the app starting |
|---|---|---|
| `database` | the app's database can be reached and the login works; each platform's tables are at the version this release needs | yes |
| `key-service` | the key service (Vault) answers, the app may sign in, the customer's keys are there and usable | only if the product needs the customer's keys |
| `secret-store` | each secret named in `platform.monitoring.secrets` is in the secret store (the names only are shown) | no |
| `login-system` | your login system's addresses (discovery and signing keys) answer, for the active login connection | no |
| `licence` | the licence is there, signed, yours, started; how many days are left, grace, expired | no |
| `channel-mail` | the mail settings are complete and the mail server can be reached (nothing is sent); it uses the settings the notifications sender uses (`platform.notifications.mail.*`: the port from the security when none is set, and with Microsoft 365 the default host and the sender mailbox) | no |
| `folder-<name>` | a folder exists, the app may read and write it, and its disk is not nearly full (warning 90 %, down 95 %) | if you set `required` |
| `service-<name>` | another service the app uses answers on its health address | if you set `required` |
| `integration-<id>` | each switched-on integration's other system answers (as on its health check) | **never** |
| `model-governance` | with model governance: its tables are at the version this release needs (down with the `MG_*` code), no drift is left without a recorded action (`MOD_DRIFT_UNHANDLED`) and no model review is overdue (`MOD_REVIEW_OVERDUE`), both having problems; runs every 5 minutes | no |

A check is **working**, **having problems** (it still works, but needs attention soon: a licence ending, a disk 90 %
full, a busy system) or **down**.

## 2. Settings (`platform.monitoring.*`)

| Property | Default | |
|---|---|---|
| `platform.monitoring.enabled` | `true` | `false` switches the checks off |
| `platform.monitoring.every` | `60s` | how often each check runs, unless the check sets its own interval (the `model-governance` check runs every 5 minutes); more than zero |
| `platform.monitoring.tick` | `5s` | how often the app looks for checks that are due (at least 0.1 s is used); more than zero |
| `platform.monitoring.startup-checks` | `true` | refuse to start while a required check is down |
| `platform.monitoring.customer-keys-required` | `false` | `true` when the product's `customerKeys` is `required`: the key service is then required at start-up |
| `platform.monitoring.product-code`, `.tenant-id` | none; tenant from `platform.licensing.tenant-id` | for the login-system check |
| `platform.monitoring.secrets` | none | secret references that must resolve, comma-separated (never the secrets) |
| `platform.monitoring.folders.<name>.path` | none | a folder to check; `.writable` (true), `.warn-at` (90), `.down-at` (95), `.required` (false). `warn-at` and `down-at` are per cent of the disk in use, each 1 to 100, and `warn-at` must not be above `down-at` |
| `platform.monitoring.services.<name>.url` | none | another service's health address (http or https); `.expected-status` (200, an HTTP status 100 to 599), `.timeout` (5s, more than zero), `.required` (false) |

A setting that is missing or not valid stops the app at start-up with `MON_SETTINGS_INVALID`. The message lists
every problem, each with its property name (for example
`platform.monitoring.folders.export.warn-at must not be above down-at`), never a value.

## 3. When the app refuses to start

If a required check is down, the app stops at start-up and its log says, one line per check, what failed, why and
what to do, for example:

```
The app cannot start: 1 required dependency is down.
  database: IDN_VERSION_OLDER: The identity tables are older than the code: the schema step for this release was not run. Fix: Run the identity schema step of this release (Flyway location db/platform/identity), then start the app.
```

Do what the line says, then start the app again.

## 4. The Support screen and the bundle

The app's Support screen lists every check with its state, code, cause and fix. From there IT can download a
**diagnostic bundle** (a zip) to send to support when the app cannot reach us. It holds the versions, Java and the
operating system, part of the configuration **with every secret removed** (passwords, keys, tokens; secret references
and addresses stay), the latest check results, the last failures (code, reference, cause, time) and the database table
versions, with a checksum of each part. It never holds document content, field values, tokens or secrets.

The configuration part is an allow-list. Only application files, the command line and `spring.application.json` are
read, never mounted secret files (config trees), a vault or config server, the environment or system properties. Only
names starting with `platform.`, `spring.application.`, `server.` or `management.` are included. The app adds its own
prefixes with `platform.monitoring.bundle-properties` (for example `myapp.`); never list a prefix that holds secrets.

## 5. Health addresses for your probes

| Address | Use it for | Up when |
|---|---|---|
| `/actuator/health/liveness` | the liveness probe (restart when it fails) | the app runs; never depends on other systems |
| `/actuator/health/readiness` | the readiness probe (send traffic) | the app is ready **and** every required check works; integrations never count |
| `/actuator/health/dependencies` | a dashboard | always up; lists every check with its state and code |

Probes need `management.endpoint.health.probes.enabled=true` outside Kubernetes. Expose the health endpoint only to
your monitoring network. Probes need no details: set `management.endpoint.health.show-details=when-authorized` with
`management.endpoint.health.roles` for your monitoring users, so nobody else sees the names and codes of your systems.

## 6. Metrics and logs

With a metrics system (Micrometer), the app publishes `platform.monitoring.check.state` (0 working, 1 problems,
2 down), `platform.monitoring.check.duration` per check, and `platform.integration.attempts` per integration and
outcome. Each failure is one log line `code=… ref=… cause="…"`; Splunk and Datadog read these key=value fields
(`docs/design/monitoring.md`).
