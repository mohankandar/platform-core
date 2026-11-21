# platform-fields — case field definitions (agreed 2026-11-21)

Source: the owner's Shared capabilities thread (project doc `Case-Fields-Design.md`). Coding sessions read this copy. Do not change the rules without the owner.

## Why

Case fields carry the business's own labels and values (contract number, member number, loan number, first/last name, address, patient ID…). Today LexaVault (`business_identifier_definitions`) and Vero (`metadata_field_definitions`) each have their own model. **Decision (owner, 2026-11-21): the model moves to platform-core; LexaVault and Vero remove their own and use this one** (in each app's switch-over). Nothing is in production or test anywhere, so no data migration is needed.

## The model

- **Field definition**, per entity and product: stable `key` (e.g. `contract_number`), `label` ("Contract number"), `type`, rules, flags, display order, active.
- **Types:** TEXT, NUMBER (whole), MONEY (decimal, **one currency per field**, e.g. USD), DATE, EMAIL, UUID, DROPDOWN (up to 100 values), ADDRESS (**structured: street, city, state, ZIP, with a country part ready for later; US format first**), CUSTOM_PATTERN. LexaVault's existing NUMERIC/ALPHANUMERIC/ALLOW_LIST map to NUMBER/TEXT-with-pattern/DROPDOWN.
- **Rules:** required, exact/min/max length, pattern, normalisation (case, strip dashes and spaces). Values up to 512 characters.
- **Flags:** primary identifier, searchable, **sensitive** (value encrypted at rest, hidden in logs, shown only to roles that need it), **multi-value** (e.g. two borrowers), displayable.
- **App-only settings stay in the app** (e.g. LexaVault "AI-extractable"; Vero "record title"), attached by key, not in the shared model.

## Rules

1. **Each app keeps its own field definitions per entity**, using this shared model. A customer may buy only one product.
2. **Vero can import LexaVault's field list** (export/import file in a shared format) so nobody types it twice. **Matching is by key.**
3. **LexaVault → Vero delivery carries each field as key + type + value** (plus the multi-value list), so Vero knows exactly what it received. A value whose key Vero doesn't know, or whose type differs, is refused with a specific code.
4. **After values exist:** the label may change any time; **key and type never change** (old documents would no longer match). To change a type, add a new field.
5. **Removing a field = switching it off, never deleting.** Existing values stay.
6. **Sensitive fields (owner, 2026-11-21, option A):** a sensitive case field (e.g. borrower name, address) is visible in the document (it is a case field, so not redacted in that entity) and stored **only encrypted**, never as readable text in any table, log or backup. It is **searchable by exact match only**, through a one-way fingerprint (keyed hash) of the normalised value; matching ignores capitals and extra spaces. No partial search on sensitive fields; partial search only on non-sensitive fields (e.g. contract or loan number). Name and address fields are marked sensitive by default; the Business Admin decides. Encryption and the fingerprint key come from the shared secrets/encryption capability (built with value storage, next task).
7. App rules stay in the apps, e.g. LexaVault: a case field and a redaction item are mutually exclusive per entity; SSN, card number, CVV, PIN and other locked items can never be case fields.
