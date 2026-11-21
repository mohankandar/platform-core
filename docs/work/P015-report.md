# P015 report — e-mail sign-in for Microsoft 365 (OAuth)

Built on `feature/mail-oauth` (from `main`), merged into `main` and pushed.

## Result

- `mvn verify`: **579 tests, 0 failures**. That is the 563 existing tests, all still passing, plus 16 new ones.
  `mvn install` ran with the same result.
- New tests by module:
  - platform-notifications: 11 (`MicrosoftOAuthMailTest`)
  - platform-notifications-spring: 5 (`MicrosoftMailConfigTest`)
- No existing test was changed. The scripted SMTP server, which is test support, can now also answer
  `AUTH XOAUTH2`.
- **Merge:**
  - `main` was fast-forwarded from `f3855ec` to `5927770` and pushed.
  - `feature/mail-oauth` was deleted locally. It never existed on origin, because nothing asked for it to be pushed
    before the merge, so there was nothing to delete there. I checked with `git ls-remote`.
  - This task file and this report were then committed on `main` and pushed.

## What was built

### platform-notifications

- **`MailAuth`**: `PASSWORD` (the default; user name and password, or a relay) or `OAUTH2_MICROSOFT`.
- **`MicrosoftOAuth`**: the Entra app's settings.
  - It holds the directory (tenant) ID (a GUID, or the verified domain), the client ID (a GUID), the credential
    type, the secret reference, and the sign-in address. The default sign-in address is
    `https://login.microsoftonline.com`; a national cloud can use another.
  - It works out the token endpoint `…/<directory>/oauth2/v2.0/token`. The scope is
    `https://outlook.office365.com/.default`.
- **`MailServer`** gains an optional `oauth` part.
  - `MailServer.microsoft365(mailbox, oauth)` gives `smtp.office365.com:587` with STARTTLS. It signs in as the
    mailbox and sends from it.
  - The old 9-argument constructor still works, so the password set-up is unchanged.
- **`MicrosoftTokens`** fetches and holds the tokens.
  - It uses the client-credentials flow over the JDK `HttpClient`. It does not follow redirects.
  - It keeps a token until 5 minutes before it expires, or half its lifetime if that is shorter. The next send then
    fetches a new one.
  - It drops the token when the mail server refuses it. A permission that an admin grants later is only in a new
    token.
  - The credential is read through `SecretResolver` only when a token is fetched. The token is held as `Secret`,
    whose text form shows only "redacted".
  - `status()` gives the state, the code and two times, for health.
- **Certificate credential (`ClientAssertion`).**
  - The secret store holds one PEM text: the certificate and its unencrypted PKCS#8 RSA private key.
  - Each token request sends a short JWT (RS256) signed with that key, as Entra expects: `x5t` is the SHA-1
    thumbprint, `aud` is the token endpoint, `iss` and `sub` are the client ID, and it carries `jti`, `nbf` and
    `exp`.
  - The key is checked against the certificate first. An expired certificate is refused before Microsoft is asked.
- **`EmailChannel`, Microsoft 365 mode:**
  - It sets `mail.smtp.auth.mechanisms=XOAUTH2` and passes the token where a password would go. It never tries a
    password login.
  - The server's refusal is read only to choose the code (see the table below); the text is never kept.
- **`TokenJson`**: a small, strict reader for the token reply. The module had a JSON writer only.

New failure codes:

| Code | When | Retried |
|---|---|---|
| `MAIL_OAUTH_TOKEN_UNREACHABLE` | Connection or timeout error, HTTP 5xx or 429 (Retry-After is passed on) | yes |
| `MAIL_OAUTH_CREDENTIAL_REFUSED` | `invalid_client` / `unauthorized_client` (wrong or expired secret or certificate), or a certificate expired here | no |
| `MAIL_OAUTH_APP_UNKNOWN` | Microsoft codes AADSTS700016, 90002, 900023 or 90072: directory or client ID unknown | no |
| `MAIL_OAUTH_CREDENTIAL_INVALID` | The secret store's text is not a certificate with its matching RSA key | no |
| `MAIL_OAUTH_TOKEN_REFUSED` | Any other 400, 401 or 403 from the token endpoint | no |
| `MAIL_OAUTH_UNEXPECTED_REPLY` | A 200 reply that is not a token (for example a proxy page), or a redirect | no |
| `MAIL_OAUTH_SEND_AS_NOT_ALLOWED` | XOAUTH2 refused other than for "SMTP AUTH disabled" (5.7.3, 5.7.139 "credentials incorrect"), or `SendAsDenied` | no |
| `MAIL_SMTP_AUTH_DISABLED` | "SmtpClientAuthentication is disabled", or the server does not offer XOAUTH2 | no |

The existing mail codes still apply once a token is accepted: refused recipient, rejected message, "try later",
TLS failure, timeout. So do `SECRET_NOT_FOUND` and `SECRET_UNAVAILABLE`.

### platform-notifications-spring

- **New properties:**
  - `platform.notifications.mail.auth`: `PASSWORD` (default) or `OAUTH2_MICROSOFT`.
  - `mail.oauth.directory-id`, `client-id`, `credential-type` (`CERTIFICATE` or `SECRET`), `credential-secret`,
    `sender-mailbox`, and `authority` (optional).
  - In Microsoft 365 mode, host, port and security default to `smtp.office365.com`, 587 and STARTTLS, and
    `from-address` defaults to the mailbox.
- **Checks at startup.** Each problem names its key and says what to give. Problems found:
  - each missing or malformed ID;
  - a wrong credential type;
  - an `authority` that is not https;
  - `security=NONE` in Microsoft 365 mode (the token must travel encrypted);
  - `username` or `password-secret` set in Microsoft 365 mode;
  - `oauth.*` set without `auth=OAUTH2_MICROSOFT`;
  - an unknown `auth` value.

  No value is ever repeated. The test pastes a client secret into each of five fields and checks that it is not in
  any message.
- **Wiring.** A `MicrosoftTokens` bean is shared by the e-mail sender and the health indicator.
- **Health.** `/actuator/health/notifications` has a new `mail` entry:
  - `auth`: `PASSWORD` or `OAUTH2_MICROSOFT`;
  - for Microsoft 365, `signIn`: *Not fetched yet*, *Token valid*, *Failing* or *No token held*;
  - when failing, `code` and `reason`;
  - `lastTokenFetch` and `tokenExpiresAt`.

  It shows no token, secret, client ID or address.

### Docs

- `docs/install/notifications.md`: a new section **1a. E-mail through Microsoft 365**, the new settings, the health
  entry, and a table of what each code means.
- `docs/design/notifications.md`: "Added in P015".
- `docs/design/setup.md`: the owner answers to P014 (new section 5), and a new section 2a.
- CLAUDE.md: the module table.

## Tests asked for → where

| Asked for | Test |
|---|---|
| Token cached and refreshed | `sendsAsTheMailboxWithXoauth2AndKeepsTheTokenUntilShortlyBeforeItExpires`: one token for several sends, a new one with 4 minutes left. Also `aShortLivedTokenIsRefreshedAtHalfItsLifetime` |
| Each failure code | `eachTokenEndpointFailureHasItsCode` (10 replies, plus Retry-After), `anUnreachableTokenEndpointIsTemporary`, `credentialProblemsFoundBeforeAskingMicrosoft` (missing secret, bad PEM, key of another certificate, expired certificate, store down), `eachMailServerRefusalOfTheTokenHasItsCode` (SMTP AUTH disabled, 5.7.3, 5.7.139, SendAsDenied, no XOAUTH2 offered, then the existing recipient and "try later" codes) |
| Retry vs no retry | `temporaryFailuresAreRetriedByTheOutboxAndPermanentOnesAreNot`, through the real outbox on Postgres. Every case above also checks `retryable()` |
| No token, secret or certificate in any output | `noTokenSecretOrKeyInAnyOutput`: all logs captured at every level, plus results, statuses and the fetch result. It looks for both tokens, the client secret, the private key, the client assertion's signature, the base64 XOAUTH2 string and Microsoft's error description. The Spring side: `aSecretPastedIntoAnyFieldIsNeverRepeated` and the health test |
| Password mode unchanged | `passwordSignInIsUnchanged`: even against a server that offers XOAUTH2, it uses PLAIN or LOGIN, fetches no token, and a refusal stays `MAIL_LOGIN_REFUSED`. Also `passwordModeIsTheDefaultAndUnchanged`, and all existing mail tests unchanged |
| Certificate credential | `theCertificateCredentialSendsASignedClientAssertion`: the fake endpoint checks the signature with the certificate and checks `x5t`, `aud`, `iss`, `sub` |
| Startup checks | `eachMistakeIsNamedWithItsKey`, `microsoft365NeedsOnlyTheIdsTheSecretReferenceAndTheMailbox` |
| Health | `healthShowsTheSignInModeAndTokenStateWithoutSecrets` |

The test certificates are made with the JDK's `keytool`; nothing in them is a real key.

**One fault the tests found.** I first refused a certificate that is "not yet valid". A certificate made a second
before the check then failed now and then. A server clock a little behind would do the same in production. Only
expiry is checked here now; "not yet valid" is left to Microsoft.

## Customer-IT steps (summary of section 1a)

1. **Register an app** in Microsoft Entra (single tenant). Note the Application (client) ID and the Directory (tenant)
   ID.
2. **Add the permission:** *Office 365 Exchange Online* → application permission **SMTP.SendAsApp** → *Grant admin
   consent*.
3. **Add a credential:**
   - **Certificate (recommended):** for example made with `openssl req -x509 …`. Upload the certificate, and save
     the certificate and private key together as one secret in the secret store.
   - **Or a client secret:** save its Value in the secret store, and note when it expires.
4. **Register the service principal** in Exchange Online with `New-ServicePrincipal -AppId … -ObjectId …`. The
   Object ID comes from *Enterprise applications*.
5. **Grant it the sender mailbox** with `Add-MailboxPermission -Identity <mailbox> -User <Object ID> -AccessRights
   FullAccess`.
6. **Enable SMTP AUTH for that mailbox** with `Set-CASMailbox -Identity <mailbox> -SmtpClientAuthenticationDisabled
   $false`.
7. **Open the firewall** to `login.microsoftonline.com:443` and `smtp.office365.com:587`.
8. **Give the installer only:** directory ID, client ID, `CERTIFICATE` or `SECRET`, the secret's path, and the
   sender mailbox.

The second customer, with their own Exchange server, keeps section 1 (password or relay) as before.

## Decisions I had to make

1. **`credential-type` is required in Microsoft 365 mode.** Setup does not guess the type from the secret's
   contents, so a wrong item in the secret store shows up as a clear code.
2. **Certificate format in the secret store:** one PEM text with the certificate and an unencrypted PKCS#8 RSA key.
   That is what `openssl req -x509 -nodes` writes. RSA only, because Entra certificates for this flow are RSA.
3. **The client assertion uses `x5t`** (SHA-1 thumbprint), as Entra documents for client assertions.
4. **SMTP AUTH disabled and "not allowed" are told apart by Microsoft's reply.** "SmtpClientAuthentication is
   disabled", or no XOAUTH2 offered, is `MAIL_SMTP_AUTH_DISABLED`. Every other refused XOAUTH2 login, and
   `SendAsDenied`, is `MAIL_OAUTH_SEND_AS_NOT_ALLOWED`. This is the only place that reads reply text, and it does
   not keep it.
5. **Four codes beyond the four asked for:** `MAIL_OAUTH_APP_UNKNOWN`, `MAIL_OAUTH_CREDENTIAL_INVALID`,
   `MAIL_OAUTH_TOKEN_REFUSED` and `MAIL_OAUTH_UNEXPECTED_REPLY`. Each points IT to a different step.
6. **Only `MAIL_OAUTH_TOKEN_UNREACHABLE` is retried** (along with the existing temporary codes). A proxy page in
   place of a token is permanent; it will not go away by itself.
7. **A refused token is dropped**, so the next send uses a new one. A permission granted after the alert then works
   without a restart.
8. **`security=NONE` is refused in Microsoft 365 mode** at startup. The core types still allow it, because the tests
   use a plain local server.
9. **The token endpoint uses the mail timeouts:** connect timeout, and connect plus read timeout per request. There
   are no separate settings.
10. **Health shows no client ID and no mailbox.** It shows only the mode, state, code and times, following the
    existing "no addresses or logins in health" rule.
11. **A certificate is checked here for expiry only**, not for "not yet valid" (see the fault the tests found).
12. **Section 2a of `docs/design/setup.md` did not exist.** I created it with only your sentence: shared database
    with row-level security for hosting later, one customer per database on-premises.

## Questions

1. **Section 2a:** it now holds only the decision. Should the row-level-security design be its own task?
   *Recommendation:* yes, before any hosting work starts.
2. **Certificate rotation:** Entra allows two certificates at once, but the secret store holds one. Should the app
   accept a second, "next" certificate reference? *Recommendation:* no. Rotate by replacing the secret's content once
   the new certificate is uploaded; the next token fetch picks it up.
3. **Exchange RBAC for Applications** (`New-ManagementRoleAssignment` scoped to the mailbox) is Microsoft's newer way
   to limit an app to one mailbox. Is `Add-MailboxPermission` enough for now? *Recommendation:* document
   `Add-MailboxPermission` now, which is what Microsoft documents for SMTP OAuth, and add the RBAC option when a
   customer asks.
4. **Graph `sendMail`** instead of SMTP. Microsoft favours it for apps, and it avoids SMTP AUTH. Should it be added
   later? *Recommendation:* not now. Revisit if a customer's policy blocks SMTP AUTH altogether.
