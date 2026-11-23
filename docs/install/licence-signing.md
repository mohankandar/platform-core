# Licence signing with Vault Transit — set-up guide (our side only)

For the people who issue licences: the owner and the backup signer. Customers never see this; their apps only ship
public keys.

The issuing command (`IssueLicenceCommand`) signs licences and last-seen resets with an **Ed25519 key inside
HashiCorp Vault's Transit engine**. The command sends Vault the bytes to sign and gets the signature back. The private
key is created in Vault, used in Vault, and never leaves it: it is not exportable, not backed up in plain text, and no
policy here can read it. Every signature is in Vault's audit log, and in our run log with the Vault request ID.

Tested with Vault 1.17.6 (the Transit sign, keys and userpass login APIs it uses are long-standing; other versions are
expected to work but are not tested).

## 1. One-time set-up (a Vault administrator)

### 1.1 Audit log

Turn on an audit device first, so every signing is recorded from the start:

```sh
vault audit enable file file_path=/vault/logs/audit.log
```

Each sign request appears with its path (`transit/sign/lic-2026`), the user who signed (`display_name`), the time and
a `request_id`. Request bodies are stored as HMACs, so the audit log holds no licence content.

### 1.2 Transit and the signing key

```sh
vault secrets enable transit
vault write -f transit/keys/lic-2026 type=ed25519
```

- Name keys `lic-<year>` (lower case letters, digits, `.`, `_`, `-`). The policy below covers every `lic-*` key.
- **Never** set `exportable=true` or `allow_plaintext_backup=true`. Vault cannot switch them back off, and the
  issuing command refuses such a key (`ISSUE_KEY_EXPORTABLE`). If it happens, create a new key and stop using the old
  one.
- Check: `vault read transit/keys/lic-2026` shows `type ed25519`, `exportable false`, `allow_plaintext_backup false`.

### 1.3 The `licensing-signer` policy

Sign with `lic-*` keys and read their public keys. Nothing else: no export, no backup, no rotation, no new keys, no
other keys.

```hcl
# licensing-signer: sign with the lic-* Transit keys and read their public keys. Nothing else.
path "transit/sign/lic-*" {
  capabilities = ["update"]
}

path "transit/keys/lic-*" {
  capabilities = ["read"]
}

# Never: export or back up a private key, even if another policy would allow it.
path "transit/export/*" {
  capabilities = ["deny"]
}

path "transit/backup/*" {
  capabilities = ["deny"]
}
```

Save it as `licensing-signer.hcl` and load it:

```sh
vault policy write licensing-signer licensing-signer.hcl
```

The tests run the issuing command against a Vault with exactly this policy and check that export, backup, rotation,
key creation and other keys are refused.

### 1.4 Two named accounts: owner and backup

Signing is done by two named people: the owner and one backup. Their user names exist **only in Vault**, never in
this repository, in scripts or in the run log's own fields (Vault's audit log records who signed).

With Vault's userpass method:

```sh
vault auth enable userpass
vault write auth/userpass/users/<owner-name>  token_policies=licensing-signer token_ttl=15m token_max_ttl=1h password=-
vault write auth/userpass/users/<backup-name> token_policies=licensing-signer token_ttl=15m token_max_ttl=1h password=-
```

`password=-` reads the password from the terminal, so it is not left in the shell history. Use strong, different
passwords; turn on Vault login MFA (TOTP) if your Vault supports it (then log in with the Vault CLI and use
`VAULT_TOKEN`, see 2.2). If you sign in to Vault through your company's single sign-on (OIDC) instead, give the same
`licensing-signer` policy to the owner's and the backup's identities, and use `VAULT_TOKEN`.

Nobody else gets this policy. The administrator who rotates keys uses a separate policy and does not sign.

## 2. Signing

### 2.1 Where Vault is

```sh
export VAULT_ADDR=https://vault.internal:8200     # or --vault-addr
export VAULT_NAMESPACE=licensing                  # only on Vault Enterprise / HCP, if you use namespaces
```

The Vault options, the same for every form of the command (licence, last-seen reset, key export):

| Option | Default | What it is |
|---|---|---|
| `--vault-addr` | `VAULT_ADDR` | Vault's address. One of the two is required. |
| `--vault-mount` | `transit` | The path where the Transit engine is mounted, if not `transit`. |
| `--vault-login` | `token` | `token` (from `VAULT_TOKEN`) or `userpass` (2.2). |
| `--vault-user` | none | Your Vault user name, with `--vault-login userpass` only. |
| `--vault-login-mount` | `userpass` | The path where the userpass method is mounted, if not `userpass`. |

`VAULT_NAMESPACE` is read from the environment only.

The command uses the Java runtime's trust store and proxy settings. If Vault's certificate is signed by an internal
CA, add that CA to the trust store the command runs with (`-Djavax.net.ssl.trustStore=…`); otherwise the command stops
with `ISSUE_VAULT_TLS`.

### 2.2 Credentials

Either log in as yourself when the command runs (it asks for the password without showing it, and revokes its token
when it ends):

```sh
issue-licence … --vault-login userpass --vault-user <your-name>
```

or give it a token for the run, for example after `vault login -method=userpass username=<your-name>`:

```sh
export VAULT_TOKEN="$(vault print token)"
issue-licence …
unset VAULT_TOKEN
```

The token and the password are never printed, logged, written to the records or the run log, or put in an error
message.

### 2.3 Issue a licence

```sh
issue-licence --customer "Example Customer Ltd" --products PRODUCT_A --kind PRODUCTION \
  --starts 2026-12-01 --expires 2027-11-30 --deployment ON_PREM \
  --slot PRODUCT_A:PAGES_PER_MONTH=10000 \
  --key-id lic-2026 --records issued.jsonl --out licence.json
```

`--key-id lic-2026` signs with the key's latest version and writes that version into the licence (`lic-2026:2`).
`--key-id lic-2026:1` signs with version 1. Last-seen resets (`--reset-last-seen …`) take the same `--key-id` and
Vault options.

### 2.4 Public keys for the release

Apps ship the public keys they trust. Export them from Vault (public keys only):

```sh
issue-licence --export-trusted-keys lic-2026 --out trusted-keys.json --run-log issued.jsonl.runs.log
```

This writes every version of the key in the trusted-keys format (`platform-licence-keys/1`) and prints the install
config lines. Name several keys with commas (`lic-2026,lic-2027`). Key IDs with a version must be in brackets in the
app config so Spring keeps the `:`:

```yaml
platform:
  licensing:
    trusted-keys:
      "[lic-2026:1]": MCowBQYDK2VwAyEA...
```

## 3. Rotation

A Vault administrator (not a signer) rotates the key:

```sh
vault write -f transit/keys/lic-2026/rotate
```

The key now has version 2, which is key ID `lic-2026:2`. New licences are signed with it. Licences signed with
`lic-2026:1` keep verifying, because apps keep trusting `lic-2026:1`. Then:

1. Export the public keys again (2.4): the file now holds `lic-2026:1` and `lic-2026:2`.
2. Ship that file with the next release. Until a customer has that release, give them licences signed with a version
   their release trusts (`--key-id lic-2026:1`).
3. Never trim old key versions (`min_decryption_version`, `trim`) while licences signed with them are in use.

A new key for a new year (`lic-2027`) works the same way; export both names.

## 4. What is recorded

| Where | What |
|---|---|
| Vault audit log | every request: sign, key read, login, token revoke; who, when, `request_id` |
| Run log (`issued.jsonl.runs.log`) | one line per run: outcome, code, licence ID, version, tenant, kind, `keyId` with its version, `signer: vault-transit`, `signerAuditRefs` (the Vault request IDs of the run's signatures) |
| Issued records (`issued.jsonl`) | one line per licence, with its `keyId`; no signature, no key, no token |

Match a run to Vault's audit log by the request IDs in `signerAuditRefs`.

## 5. Refusals

The command stops with a stable code and the exact cause; nothing is written when signing fails. Every code is also
in `docs/errors/platform-error-codes.md`.

| Code | Cause | What to do |
|---|---|---|
| `ISSUE_VAULT_NO_TOKEN` | `VAULT_TOKEN` is not set and no `--vault-login` was given | Log in (2.2) |
| `ISSUE_VAULT_LOGIN_REFUSED` | wrong user name or password, user disabled, or login MFA needed | Check the name; with MFA use the Vault CLI and `VAULT_TOKEN` |
| `ISSUE_VAULT_PERMISSION_DENIED` | the token lacks the `licensing-signer` policy, or expired | Log in again; ask the Vault administrator for the policy |
| `ISSUE_VAULT_UNREACHABLE` | connection refused, timeout or unknown host | Check `VAULT_ADDR` and the network |
| `ISSUE_VAULT_TLS` | Vault's certificate is not trusted | Add the CA to the Java trust store (2.1) |
| `ISSUE_VAULT_SEALED` | Vault answered HTTP 503: it is sealed (or otherwise not able to serve) | Unseal Vault |
| `ISSUE_VAULT_ERROR` | another Vault error or an unexpected reply; also a standby node that redirects (redirects are not followed, the output says "use the active node's address") | Read the HTTP status and Vault's message in the output; for a standby node, point `--vault-addr` / `VAULT_ADDR` at the active node |
| `ISSUE_KEY_NOT_FOUND` | no such Transit key, or no such version | Check `--key-id` and `vault read transit/keys/<name>` |
| `ISSUE_KEY_INVALID` | the key is not of type `ed25519` | Create an `ed25519` key |
| `ISSUE_KEY_EXPORTABLE` | the key allows export or plaintext backup | Create a new key without them (1.2) |
| `ISSUE_SIGNATURE_MISMATCH` | Vault's signature does not verify with the key's public key | Stop and investigate; nothing was written |
