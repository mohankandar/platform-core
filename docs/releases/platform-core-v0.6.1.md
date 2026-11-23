# platform-core 0.6.1

A patch release of security fixes and dependency updates (task P043, from the independent security review P042,
`docs/work/security-findings.md`). No new modules, no schema change, no breaking API change. Every module has the
same version. Import the BOM:

```xml
<dependency>
  <groupId>com.documentplatform.platform</groupId>
  <artifactId>platform-bom</artifactId>
  <version>0.6.1</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

Published to GitHub Packages, `https://maven.pkg.github.com/mohankandar/platform-core`.

**Every app that uses folder export or the watched folder (platform-integration) should take 0.6.1:** it fixes
SEC-1, rated **High**.

**No other product's name or behaviour in code.**

## Fixes

1. **SEC-1 (High): links are never followed when the platform deletes, moves or walks folders.**
   - Modules: `platform-integration` (folder export, watched folder) and `platform-erase-log`.
   - On Windows, a junction placed where the platform cleans up was followed, and everything behind it was deleted
     under the service account's rights. A junction named `<package>.partial` in an export folder could delete the
     erase log, the app's data or its configuration. The review proved this on Windows.
   - Now `LocalFiles` refuses a symbolic link, a junction or any other reparse point at, or anywhere inside, what it
     deletes, renames or moves. It checks the whole tree before deleting anything, and touches nothing behind the
     link.
   - Folder export answers `INT_FOLDER_LINK_REFUSED`. This covers a link where the package folder or its `.partial`
     folder belongs.
   - The watched folder answers `INT_WATCH_LINK_REFUSED`. It checks `_processing`, `_archive`, `_problem` and
     `_quarantine` every pass and before every move into them, and also checks a claimed drop and its marker.
   - The erase-log store refuses when one of its own files is a link (`ERASE_LOG_LINK_REFUSED`). Its temporary files
     are always made new, so a hard link left in their place is replaced instead of written through.
2. **SEC-9 (Medium): the bytes handed to the app are the bytes the virus scanner read** (watched folder).
   - The bytes the scanner reads are now counted and hashed.
   - A clean verdict counts only for exactly the bytes checked when the drop was claimed. The app's stream accepts
     only those bytes too.
   - Before, a sender could swap a file between the hash, the scan and the hand-over. A file changed in between now
     goes to `_problem` with `INT_WATCH_FILE_CHANGED`.
3. **SEC-52 (Info): Windows device names are refused in every form** in folder and file names: `CON .txt`, `CONIN$`,
   `CONOUT$`, `CLOCK$`, `COM0` / `LPT0`, and COM / LPT with superscript digits.
4. **A refused proxy tunnel is never read as the destination's answer** (`platform-outbound`).
   - From HttpClient 5.4, a proxy that refuses the CONNECT of an https call no longer causes an exception. Its own
     answer comes back as the response.
   - A call through a proxy to an https address that has no TLS session is now the proxy's refusal:
     `OUT_PROXY_REFUSED`, or `OUT_PROXY_SIGN_IN_REFUSED` for 407 (integration: `INT_PROXY_*`).
   - Found by the tests after the update below.

## Dependency updates (SEC-14 in part)

| Dependency | Was | Now | Advisories fixed |
|---|---|---|---|
| `org.apache.httpcomponents.client5:httpclient5` | 5.3.1 | 5.6.5 | CVE-2026-64607 |
| `org.apache.httpcomponents.core5:httpcore5`, `httpcore5-h2` | 5.2.4 | 5.4.4 | CVE-2026-54399, CVE-2026-54428 |
| `com.nimbusds:nimbus-jose-jwt` (through Spring Security) | 9.37.3 | 9.37.4 | CVE-2025-53864 |

- httpcore5 and Nimbus are now declared where they are used (`platform-outbound`, `platform-identity-spring`), so
  the published POMs carry the fixed versions.
- An app whose Spring Boot dependency management pins older httpcomponents versions should override them with the
  versions above.
- **The Spring Boot, Framework and Security versions are unchanged** (compiled against 3.3.x). Moving the Spring line
  is a separate decision.
- `micrometer-core` stays an optional dependency: the app's own version is used.

## Build and release pipeline (SEC-15, SEC-40, SEC-41)

- The release workflow publishes only a tagged commit that is on `main` and whose own CI run on `main` passed.
  Otherwise it fails with a message that says why.
- Testing runs in a read-only job. Publishing runs in a separate job, which alone has write rights. No checkout
  keeps its token.
- CI and release actions are pinned by commit SHA.
- `.github/dependabot.yml` proposes Maven and GitHub Actions updates weekly. It never proposes a minor or major
  update of the Spring line.

## Testkit

`platform-integration-testkit` `Eicar.testFile()` is a harmless placeholder by default. `MockScanner` reports it as
infected exactly like EICAR. With the environment variable `RUN_EICAR_TESTS=true` it is the real EICAR test file;
CI and the release workflow set it. This keeps desktop virus scanners on developer machines from removing the file
or cutting the connection that carries it. `Eicar.bytes()` is still the EICAR file.

## API notes

No breaking change.

- `ExportFiles` (the folder port, which `WatchFiles` extends) has a new default method, `link(Path)`. An app's own
  port gets it without changes.
- New public `com.documentplatform.integration.folder.LinkRefusedException` (an `IOException`). A port that refuses
  links throws it.
- `ExportFiles.delete` documents the refusal of links.

## New codes (3; no code renamed or removed)

`INT_FOLDER_LINK_REFUSED`, `INT_WATCH_LINK_REFUSED` and `ERASE_LOG_LINK_REFUSED` (kind `NEEDS_PERSON`). The texts
and the regenerated catalogue are in `docs/errors/platform-error-codes.md`. The install guides list them:
`docs/install/integration.md` "Codes you can see", and `docs/install/erase-log.md` section on health.

## Database schemas

Unchanged from 0.6.0:

| Module | Location | History table | Version |
|---|---|---|---|
| platform-identity-jdbc | `db/platform/identity` | `idn_schema_history` | 4 |
| platform-integration | `db/platform/integration` | `int_schema_history` | 2 |
| platform-licensing | `db/platform/licensing` | `lic_schema_history` | 1 |
| platform-notifications | `db/platform/notifications` | its own run, any history table (for example `notif_schema_history`) | 1 |
| platform-fields | `db/platform/fields` (optional `db/platform/fields-optional/partial-search.sql`) | `fld_schema_history` | 1 |
| platform-erase-log | `db/platform/erase-log` | `erl_schema_history` | 1 |
| platform-model-governance | `db/platform/modelgov` | `mg_schema_history` | 4 |

## Requirements

Java 21. The Spring modules are compiled against Spring Boot 3.3.x; 3.5.x works too.
