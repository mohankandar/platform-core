# platform-erase-log — erase log and customer key destruction (decided 2026-11-22)

Source: owner decision (b) + (c), confirmed by the LexaVault thread (master), 2026-11-22. LexaVault's record: `docs/work/erase-and-backups.md` in secure-document-platform. Project doc: `Erase-Log-Design.md`. Related: `docs/design/case-fields.md` ("Erasing a record", "Erase and backups"), `docs/design/crypto.md`.

## The rule

- **(b) Everyday erase and retention:** erasures remove data from the live database and storage at once. Backups are kept only for the backup retention period, then destroyed. If a backup is restored, every erasure made after that backup is **re-applied from the erase log before the restored system takes any work**.
- **(c) Customer exit:** destroying the customer's keys makes all their data unreadable everywhere, backups included. The offboarding certificate rests on this.
- **(a) Per-case keys:** not now; only if a customer contract requires it.

## Backup retention

- Default **35 days**; a setting per installation, allowed **30–90 days**, set by contract. The compliance report states the period in force.

## The erase log (shared: LexaVault and Vero both write to it)

- **Kept outside the database it protects** (its own store, backed up separately), so a restore can't roll it back.
- **Holds no values:** entity, case/record ID, what was erased (e.g. document, redacted copy, case fields, export copy), when, by which rule or person, the audit record ID.
- **Kept at least backup retention + 30 days** (default 35 + 30 = 65 days); then pruned, because every backup it could apply to is gone.
- **Restore procedure:** restore → **replay the erase log** → **check** that nothing in the log is readable → only then take work. **Replaying twice is safe.**
- **Shared part:** the log store, writing an entry, replay with app-registered handlers per "what" (platform-fields' record erase is one handler; documents and copies are the apps'), the verify step, idempotency, pruning, and a guard so a restored system doesn't take work before replay.
- **LexaVault** uses it in switch-over part 1; Vero with its retention work.

## Customer key destruction (customer exit)

- Part of the crypto module (P018 follow-up): destroy a customer's wrapping and fingerprint keys in the key service. Irreversible, so an explicit command with confirmation, audited, producing the data for the offboarding certificate (customer, keys destroyed, date).
- The offboarding runbook lists it as a step; the certificate lists the keys destroyed and the date.
