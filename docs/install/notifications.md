# Notifications — install guide for IT

This page tells your IT team what to set up so the app can send notifications by e-mail, Microsoft Teams and Slack.
It also covers the database. The app's own install guide says where its install config file is. The settings below
all start with `platform.notifications`.

Two rules hold throughout:

- **No secrets in the config file.** The mail password and the Teams and Slack webhook addresses go into your secret
  store. The config file holds only the **path** of each secret (a "secret reference"), for example
  `notifications/mail-password`.
- **The app never changes the database by itself.** You create the tables with the schema step (section 4). At startup
  the app only checks them.

You only need the channels you want to use. With none configured, the app still starts and records notifications, but
sends nothing.

---

## 1. E-mail

1. **Choose the mail server** the app sends through: your company mail server or an internal relay. Note its host name
   and port, and choose the security:
   - **STARTTLS on port 587**: recommended.
   - **TLS on port 465.**
   - **None on port 25**: only for a relay inside your own network.
2. **Create a sender address** for the app, for example `notifications@yourcompany.com`. Then either:
   - give it a login that may send mail (SMTP AUTH), or
   - allow the app server's address on the relay, with no login.

   **Microsoft 365:** do not use a password. Follow section 1a instead.
3. **If there is a login,** store the sender's password in the secret store. Note the secret's path, not the password.
4. **Give the installer:** host, port, security, user name (if any), the secret's path, and the sender address and
   display name.
5. **If your own certificate authority signs the mail server's certificate,** add that authority to the app's Java
   trust store.
6. **Open the firewall** from the app server to the mail server on that port.
7. **Make sure the sender may send to everyone who will get notifications.** Messages carry
   `Auto-Submitted: auto-generated`, so out-of-office replies are held back.

One mail server serves all e-mail notifications.

## 1a. E-mail through Microsoft 365

With Microsoft 365, the app signs in to `smtp.office365.com` (port 587, STARTTLS) without a password. It uses an app
that you register in Microsoft Entra, and the app sends as one mailbox. The app's credential is a **certificate**
(recommended) or a **client secret**. It stays in your secret store, and the config holds only its path.

You need: an Entra administrator, an Exchange administrator with the Exchange Online PowerShell module, and the
mailbox the app sends from (for example `notifications@yourcompany.com`, a shared mailbox is fine).

1. **Register the app.** In the Microsoft Entra admin center, open *App registrations* and choose *New registration*.
   - Name: for example "Notifications sender"; *Accounts in this organizational directory only*; no redirect URI.
   - On its *Overview* page, note the **Application (client) ID** and the **Directory (tenant) ID**.
2. **Allow it to send mail.** In the app's *API permissions*, choose *Add a permission* → *APIs my organization uses*
   → *Office 365 Exchange Online* → *Application permissions* → **SMTP.SendAsApp**. Then choose *Grant admin consent*.
3. **Give it a credential.**
   - **Certificate (recommended):** make a certificate and key, for example
     `openssl req -x509 -newkey rsa:2048 -nodes -days 730 -subj "/CN=notifications-sender" -keyout key.pem -out cert.pem`.
     Upload `cert.pem` under *Certificates & secrets* → *Certificates*. In your secret store, save **one** secret that
     holds the text of `cert.pem` followed by the text of `key.pem` (`BEGIN CERTIFICATE` and `BEGIN PRIVATE KEY`).
     Then delete `key.pem` from the disk.
   - **Client secret:** under *Certificates & secrets* → *Client secrets*, choose *New client secret*. Copy its
     **Value** (not its ID) into your secret store at once. Note its expiry date, and replace it in time.

   Note the secret's **path** in the secret store.
4. **Register the app in Exchange Online.** In *Enterprise applications* (not *App registrations*), open the app and
   note its **Object ID**. Then, in Exchange Online PowerShell:
   ```
   Connect-ExchangeOnline
   New-ServicePrincipal -AppId <Application (client) ID> -ObjectId <Object ID from Enterprise applications> -DisplayName "Notifications sender"
   ```
5. **Let it use the sender mailbox:**
   ```
   Add-MailboxPermission -Identity notifications@yourcompany.com -User <Object ID from Enterprise applications> -AccessRights FullAccess
   ```
6. **Switch on SMTP AUTH for that mailbox** (it is off by default):
   ```
   Set-CASMailbox -Identity notifications@yourcompany.com -SmtpClientAuthenticationDisabled $false
   ```
   Changes in steps 2 to 6 can take up to an hour to reach all of Microsoft's servers.
7. **Open the firewall** from the app server to `login.microsoftonline.com` (HTTPS, port 443) and to
   `smtp.office365.com` (port 587). The token request to `login.microsoftonline.com` uses the Java proxy options
   (`https.proxyHost`, `https.proxyPort`, `http.nonProxyHosts`) when they are set, like the Teams and Slack calls.
   Proxies that ask for a login are not supported. The mail connection to `smtp.office365.com` never goes through
   that proxy: it needs a direct connection on port 587.
8. **Give the installer only:** the directory (tenant) ID, the application (client) ID, `CERTIFICATE` or `SECRET`,
   the **path** of the secret, and the sender mailbox. Never the certificate, key or secret themselves.

The installer sets:

```
platform.notifications.mail.auth=OAUTH2_MICROSOFT
platform.notifications.mail.oauth.directory-id=<Directory (tenant) ID>
platform.notifications.mail.oauth.client-id=<Application (client) ID>
platform.notifications.mail.oauth.credential-type=CERTIFICATE
platform.notifications.mail.oauth.credential-secret=notifications/m365-app-certificate
platform.notifications.mail.oauth.sender-mailbox=notifications@yourcompany.com
```

Host, port and security are filled in for Microsoft 365. Do not set `mail.username` or `mail.password-secret`.

If something is wrong, the health page (section 7) shows one of these codes:

| Code | What to check |
|---|---|
| `MAIL_OAUTH_APP_UNKNOWN` | The directory ID or the client ID is wrong (step 1). |
| `MAIL_OAUTH_CREDENTIAL_REFUSED` | The secret or certificate is wrong or has expired (step 3). |
| `MAIL_OAUTH_CREDENTIAL_INVALID` | The secret store's certificate text is not a certificate with its private key (step 3). |
| `MAIL_OAUTH_SEND_AS_NOT_ALLOWED` | The permission, consent, Exchange registration or mailbox permission is missing (steps 2, 4, 5). |
| `MAIL_SMTP_AUTH_DISABLED` | SMTP AUTH is off for the mailbox (step 6). |
| `MAIL_OAUTH_TOKEN_UNREACHABLE` | The app server cannot reach Microsoft sign-in (step 7). It is retried by itself. |
| `MAIL_OAUTH_TOKEN_REFUSED`, `MAIL_OAUTH_UNEXPECTED_REPLY` | Microsoft refused or replied oddly. A proxy in between is a common cause. |

**Your own Exchange server** keeps working as before: leave `mail.auth` out (or set `PASSWORD`) and follow section 1.

## 2. Microsoft Teams

1. In Teams, open the channel that should receive notifications. Choose **⋯ → Workflows** and pick the template
   **"Post to a channel when a webhook request is received"**. In Power Automate this is a flow with the trigger "When a
   Teams webhook request is received" and the action "Post card in a chat or channel".
2. Choose the team and channel. Set **who can trigger** to **Anyone**: the app does not sign in, and the secret part of
   the address protects it. Save, then copy the address the flow shows.
3. **Create the flow with a service account,** not a personal one. If the owner leaves or the flow is switched off, the
   address stops working, and the app reports "Teams webhook address no longer valid".
4. **Store the address in the secret store.** It works like a password. Give the installer only the secret's path,
   and a short id for this destination, for example `ops-teams`.
5. **Allow outbound HTTPS (port 443)** from the app server to the Power Platform / Logic Apps hosts
   (`*.logic.azure.com`, `*.api.powerplatform.com`). If you use a proxy, set the Java proxy options (`https.proxyHost`,
   `https.proxyPort`). Proxies that ask for a login are not supported.
6. Old "Incoming Webhook" connector addresses (`…webhook.office.com…`) were retired by Microsoft, and the app refuses
   them. Create a Workflows address instead.

Repeat for each channel that should receive notifications. Each one gets its own id and secret.

## 3. Slack

1. At `api.slack.com/apps`, create an app ("From scratch") in your workspace. Open **Incoming Webhooks**, switch it on,
   choose **Add New Webhook to Workspace** and pick the channel. A workspace admin may need to approve the app.
2. Copy the address (`https://hooks.slack.com/services/…`) and **store it in the secret store**. It works like a
   password. Give the installer only the secret's path and a short id, for example `ops-slack`.
3. **Allow outbound HTTPS** from the app server to `hooks.slack.com`, through the proxy if you use one.
4. If the channel is archived or the Slack app is removed, the app reports "Slack webhook address no longer valid". Add
   a new webhook and update the secret. The app reads secrets each time it sends, so no restart is needed.

## 4. Database

The notifications tables all start with `notif_`. There are eight: tenant, destination, event settings, outbox,
summary items, conditions, approval requests and the schema version.

**Creating and upgrading the tables is a separate schema step that you run.** It is part of the app's own schema step.
The SQL files ship inside the app under `db/platform/notifications` (`V1__notifications.sql`, …).
Notifications has **no history table of its own** (there is no `notif_schema_history`): the app's schema step runs
these files with its own tool and history table, as the app's install guide describes. The app checks the version
in the table `notif_schema_version`, which the files keep up to date.

1. **Before the first start, and after every upgrade,** run the app's schema step. Run it as a database user that may
   create tables in the app's schema, for example the schema owner.
2. **The app's runtime user needs no rights to create or change tables.** Grant it only:

   ```sql
   grant usage on schema <app_schema> to <app_user>;
   grant select, insert, update, delete on
       notif_tenant, notif_destination, notif_event_settings,
       notif_outbox, notif_summary_item, notif_condition, notif_approval_request
     to <app_user>;
   grant select on notif_schema_version to <app_user>;
   ```

   No sequence rights are needed.
3. **At startup the app checks the tables and stops if something is wrong.** The message says exactly what is wrong:

   | Code | Message starts with | What to do |
   |---|---|---|
   | `TABLES_MISSING` | `Notifications tables missing (expected version 1)` | The schema step was not run on this database schema. Run it. |
   | `VERSION_OLDER` | `Notifications tables at version N, expected version V` | The schema step for this release was not run. Run it. |
   | `VERSION_NEWER` | `Notifications tables at version N, newer than this app expects` | An older app release is running against an upgraded database. Install the matching release. |
   | `VERSION_UNKNOWN`, `TABLES_INCOMPLETE` | `Notifications tables at an unknown version` / `Notifications tables incomplete` | Tables were changed or dropped by hand. Run the schema step, or restore the database. |
   | `PRIVILEGES_MISSING` | `The app's database user lacks …` | Grant the rights in step 2. |
   | `CHECK_FAILED` | `Notifications tables could not be checked` | The database refused the check; the message gives the SQL state. |

   The codes are `NotificationSchemaException.Problem` values; each one's cause and fix are in
   `docs/errors/platform-error-codes.md`.

**What the tables hold:** ids, the admins' settings, and the text of outgoing messages. That text contains only the
event name, a record number, counts, times and a link into the app. The tables hold no passwords, no webhook
addresses, no e-mail addresses and no document content. Sent and failed messages are deleted after 30 days.
Approval requests (section 8) hold user ids, the decider's display name and the reason given for a decline; never a
link or its token.

## 5. Settings

| Key (prefix `platform.notifications.`) | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` switches notifications off completely. |
| `mail.host` | — | Mail server host. Leave out all `mail.*` keys for no e-mail. |
| `mail.port` | 587 / 465 / 25 | Defaults to the usual port for the chosen security. |
| `mail.security` | `STARTTLS` | `STARTTLS`, `TLS` or `NONE`. |
| `mail.username` | — | Login user. Leave out for a relay without a login. |
| `mail.password-secret` | — | **Path** of the password in the secret store. Required with `mail.username`. |
| `mail.from-address` | — | Sender address. Required with e-mail. |
| `mail.from-name` | — | Sender display name. |
| `mail.connect-timeout` / `mail.read-timeout` | `10s` / `30s` | How long to wait for the mail server (and for Microsoft sign-in). |
| `mail.auth` | `PASSWORD` | `PASSWORD` (user name and password, or a relay) or `OAUTH2_MICROSOFT` (Microsoft 365, section 1a). |
| `mail.oauth.directory-id` | — | Microsoft 365: the Entra directory (tenant) ID. |
| `mail.oauth.client-id` | — | Microsoft 365: the app's application (client) ID. |
| `mail.oauth.credential-type` | — | Microsoft 365: `CERTIFICATE` or `SECRET`. |
| `mail.oauth.credential-secret` | — | Microsoft 365: **path** of the certificate-and-key or client secret in the secret store. |
| `mail.oauth.sender-mailbox` | — | Microsoft 365: the mailbox the app sends as. `mail.from-address` defaults to it. |
| `mail.oauth.authority` | `https://login.microsoftonline.com` | Microsoft 365: only for a national cloud. |
| `teams.<id>` | — | One line per Teams destination. The key `<id>` is the destination id (for example `ops-teams`); the value is the **path** of the secret that holds the webhook address. |
| `slack.<id>` | — | One line per Slack destination. The key `<id>` is the destination id (for example `ops-slack`); the value is the **path** of the secret that holds the webhook address. |
| `webhook.connect-timeout` / `webhook.request-timeout` | `10s` / `30s` | How long to wait for Teams and Slack. |
| `retry.first-delay` | `1m` | First retry after a temporary failure. |
| `retry.max-delay` | `30m` | The wait doubles up to this. |
| `retry.give-up-after` | `24h` | Stop retrying a message this long after it was made, and report it. |
| `lease` | `5m` | How long one app instance holds a message it is sending. Must be longer than the timeouts added up. |
| `retention` | `30d` | How long sent and failed messages are kept; also how long approval requests are kept after they expire (purged daily). |
| `tick-interval` | `1m` | How often alerts for ongoing problems and due summaries are checked (1 s to 5 min). |
| `sender.poll-interval` | `10s` | How often the sender looks for retries and other instances' messages (1 s to 5 min). |
| `sender.batch-size` | `100` | Messages per send round (1 to 1000). |
| `approvals.link-key-secret` | — | **Path** of the key that signs approval links (section 8). Leave out for no approval links. |
| `approvals.link-path` | `/approvals/link` | The path of the app's approval page. Change it only if the app's release notes say so. |

Destination ids may use letters, digits, `.`, `_` and `-`. Lower-case ids such as `ops-teams` are simplest. To keep
capitals or `_` in an id, put it in brackets: `platform.notifications.teams[Ops_Teams]=…`.

**Example** (`application.yml`):

```yaml
platform:
  notifications:
    mail:
      host: smtp.yourcompany.com
      port: 587
      security: STARTTLS
      username: notifications@yourcompany.com
      password-secret: notifications/mail-password      # path in the secret store, not the password
      from-address: notifications@yourcompany.com
      from-name: Document notifications
    teams:
      ops-teams: notifications/teams-ops-channel        # path of the secret holding the Workflows address
    slack:
      ops-slack: notifications/slack-ops-channel        # path of the secret holding the hooks.slack.com address
```

All other settings can keep their defaults.

**At startup the app checks every setting.** If one is wrong, it stops and names each problem with its key, for
example:

```
Notifications configuration is not valid:
 - platform.notifications.mail.security: must be STARTTLS, TLS or NONE
 - platform.notifications.teams.ops-teams: not a valid secret reference (…); give the path of the secret that holds
   the webhook address, never the webhook address itself (this value looks like an address: store it in the secret
   store and give its path here)
```

The message never repeats a value that might be a secret.

### Time zones per entity

The tenant settings hold the tenant's time zone. If the app defines an `EntityZones` bean (the entity's own zone,
from `idn_entity.time_zone`, see `docs/install/identity.md` section 5), an event that belongs to an entity with its
own zone uses that zone for its daily summary time, its summary period and the times in its messages. Events without
an entity, and entities without their own zone, keep the tenant's zone. If the bean fails, the tenant's zone is used
and a warning with the entity id is logged.

The customer's zone comes from identity: when the app has platform-identity's `EntityTimeZones` as a bean (or defines
a `CustomerZones` bean), the customer's zone in `idn_customer.time_zone` replaces the tenant zone of the notification
settings for every event and summary. The settings' zone is used only when identity has none for the customer. Set
the customer's zone in one place, the app's settings screen (`EntityTimeZones.setCustomerZone`).

## 6. Several app servers

Several instances of the app may share one database. Each message is sent by one instance only, and no extra setup
is needed.

## 7. Checking that it works

With Spring Boot Actuator switched on, `/actuator/health/notifications` lists each destination. A destination
appears once the app has sent to it; Teams and Slack destinations from the config are listed from the start.

| State | Meaning |
|---|---|
| **Working** | The last message went through. |
| **Having problems** | The last attempt failed for a temporary reason and is being retried, or one message was refused (for example one recipient address). The `code` and `reason` say which. |
| **Down** | It cannot work until someone fixes something, for example "Mail server refused the login" or "Teams webhook address no longer valid". Also shown when temporary failures have gone on for 15 minutes. |
| **Not used yet** | Nothing was sent to it since the app started. |

Under `mail`, the page shows how the app signs in to the mail server: `PASSWORD` or `OAUTH2_MICROSOFT`. For Microsoft
365 it also shows the sign-in state (*Not fetched yet*, *Token valid*, *Failing* with its code, or *No token held*),
the time of the last token request and when the token expires. The token itself is never shown.

The health page shows ids, states, codes and reasons only, never an address, login or secret. A destination that is
down does not mark the whole app as unhealthy. The notifications entry shows `DEGRADED` instead.

## 8. Approval links (Approve / Decline in the message)

Some messages ask for a decision, for example "Print request waiting". They carry two links, **Approve** and
**Decline**. A link opens the app's approval page for that one request. The approver signs in as usual, sees the
request and confirms with one more click. Opening a link never decides anything, so a mail scanner that opens links
cannot approve.

- **E-mail:** each approver gets their own e-mail with their own links. A link works only for the person it was sent
  to, and only while they still hold the approver role. The requester gets no links and is told the outcome by e-mail.
- **Teams and Slack:** the links open the same page in the browser. Anyone in the channel who signs in and holds the
  approver role may use them. Nothing calls back into Teams or Slack, so no inbound connection to your network is
  needed.
- The first decision counts. Someone who clicks later sees "Already approved by … at …".
- Links stop working when the request expires (24 hours unless the app says otherwise). If the app's request type
  says so, the requester is told by e-mail when a request expires with nobody deciding (once per request).
- Old requests are deleted once a day: those that expired more than `retention` ago (default 30 days, the same
  setting as for sent messages). The app's own audit keeps every decision.

**To switch approval links on:**

1. Make a random key of at least 32 characters, for example:

   ```
   openssl rand -base64 48
   ```

2. Store it in the secret store, for example under `notifications/approval-link-key`.
3. Give its **path** (not the key) in the settings:

   ```yaml
   platform:
     notifications:
       approvals:
         link-key-secret: notifications/approval-link-key
   ```

4. Make sure the app address in the tenant settings is the address approvers reach the app on. The links are made
   under it.

**Replacing the key:** put a new key in the same secret. Links already sent stop working (the approver can still
decide in the app). No restart is needed.

**If the key is missing or unreadable**, request messages are not sent, with the code `ACTION_KEY_NOT_FOUND` (not in
the secret store), `ACTION_KEY_INVALID` (shorter than 32 characters) or `SECRET_UNAVAILABLE` (secret store down;
retried). Other codes for these messages: `ACTION_REQUEST_CLOSED` (the request was decided or expired before the
message went out; nothing is sent) and `ACTION_LINKS_NOT_AVAILABLE` (the app has approval requests but approval links
are not switched on).
