# P042 report — strength check, step 3: independent security review (read-only)

Date: 2026-11-23
Branch: `docs/p042-security-review`
Outcome: DONE

## Result

- **Output:** `docs/work/security-findings.md` lists 59 findings with a "checked and fine" list for all 13 areas and the proposed fix tasks. It changes no product code.
- **Counts per severity:** Critical 0 · High 1 · Medium 14 · Low 26 · Info 18.
- **Local `mvn -B verify`** on `main` at `7a54068`: 1414 tests, 1 failure, 11 skipped.
  - The failure is `platform-integration-testkit` `ScannerTest.eicarFromASourceNeverReachesTheHandlerAndIsNotAcknowledged`. It fails twice in a row, with `INT_TIMEOUT` and then `INT_CONNECTION_LOST` at the download of the EICAR test file.
  - The cause is this laptop's antivirus. The test's own assumption (`Eicar.staysOnDisk`) passes, but the antivirus cuts the loopback HTTP download that carries the EICAR bytes.
  - The engine fails closed: the infected file never reached the handler, and nothing infected was acknowledged.
  - The 11 skips are the TLS tests that skip where TLS to 127.0.0.1 is intercepted (as CLAUDE.md says for this laptop).
  - CI on `main` is green for the same commit (`7a54068`, run "CI" success). The gate for this PR is CI.
  - Proposed: the test should also skip when the download, not only the file on disk, is blocked (question 3).
- **PR, CI run and merge:** see the PR description of this branch (this report is part of that PR).

## Independence

No other product's name or behaviour in code. This review added no code. The findings file names third-party login systems and tools only as examples of formats; `docs/work/` is not checked by `NoProductNamesGuardTest`.

## What was built

Nothing in product code. The review was done in two parts:
- **Areas 1–11 and 13:** six independent read-only reviewers, one per group of areas.
- **Area 12** (dependencies, workflows, repo settings): reviewed directly.

Every Medium and High finding was then checked again in the code before it was written down:
- SEC-1, 2, 3, 4, 6, 7, 8, 11, 12, 15 were read again in the code;
- SEC-1 was also shown by running it;
- SEC-11, SEC-19, SEC-20 and SEC-22 were run by the reviewer against the built classes.

**Proof-of-concept tests:** none were written in the repo, so **no proof-of-concept branch was created, and none was pushed or needs deleting.** The one executed proof (SEC-1) ran in the session's scratch folder, outside the repo:
1. `JunctionDelete.java` is an exact copy of `LocalFiles.delete` (`platform-integration/.../folder/LocalFiles.java:111-131`).
2. It made `victim\keep.txt`, ran `cmd /c mklink /J export\P1.partial victim`, then called `delete(export\P1.partial)`.
3. The output was `isDirectory(NOFOLLOW) on junction: true`, `before: … true`, `after: … false`.

The junction was followed and the file behind it deleted.

### Top findings in plain words

1. **SEC-1 (High).** On Windows, anyone who can write to an export share or a watched folder can make the service delete any file it can reach, including the erase log. They do it by planting a folder shortcut (a junction) where the service cleans up. I ran this on this machine.
2. **SEC-2 (Medium).** The key-service rights given to the setup step are wider than documented. Setup can permanently throw away old key versions, and so make customer data unreadable, without the separate "offboarding" right.
3. **SEC-3, SEC-4, SEC-5 (Medium).** The erase log's promises have gaps:
   - its chain has no secret, so someone who can write the log folder can forge erasures;
   - a database restored while the app keeps running is not noticed;
   - field values can be erased through public classes without ever being logged, so they come back after a backup restore.
4. **SEC-6 (Medium).** The model-governance gate at model start does not check that the live validation was approved. App code, or a direct database write, can make an unapproved model pass.
5. **SEC-7 (Medium).** A person's sign-in token can be taken for a machine caller of the push API when the bound client also allows people to sign in.
6. **SEC-8, SEC-9, SEC-10 (Medium).**
   - A captured signed callback can be resent under a new ID.
   - A sender can swap a file between the virus scan and the hand-over.
   - One entity's integration can name another entity's secret.
7. **SEC-11 (Medium).** The support bundle copies mounted secret files (Kubernetes config trees, vault sources) into the zip in clear.
8. **SEC-12, SEC-13 (Medium, commercial).** The customer supplies the licence's trusted keys, so they can sign their own licence. The app's database user can also reset the "latest time seen" and bring an expired licence back.
9. **SEC-14, SEC-15 (Medium).**
   - The Spring 6.1, Boot 3.3 and Security 6.3 baseline is past open-source support and has open advisories.
   - The release workflow publishes any matching tag, even on a commit that never went through a PR.

### Dependency check

- **Tool:** the GitHub advisory database, through `gh api graphql` (`securityVulnerabilities`, ecosystem MAVEN), on 2026-11-23. The owner chose this over OWASP Dependency-Check.
- **Input:** the 81 third-party artifacts resolved by `mvn dependency:list` (all scopes) over the reactor. The versions were matched against each advisory's vulnerable range, and withdrawn advisories were left out.
- **Compile or runtime scope, so it reaches apps:**

  | Artifact | Version | Advisories (first fixed version, or "none" when the line has no open-source fix) |
  |---|---|---|
  | spring-boot / -autoconfigure | 3.3.2 | CVE-2025-22235 High (3.3.11); CVE-2026-40973 High (none); CVE-2026-41001 Moderate (none) |
  | spring-security-web | 6.3.1 | CVE-2024-38821 Critical, WebFlux only (6.3.4); CVE-2026-22732 Critical (none); CVE-2026-47838 Moderate (none); CVE-2026-41706 Moderate (none) |
  | spring-security-core / -crypto / -oauth2-jose | 6.3.1 | CVE-2025-22228 High (6.3.8); CVE-2024-38810 (6.3.2); CVE-2024-38827 (6.3.5); CVE-2026-22748 (none); CVE-2026-22746 Low (none) |
  | spring-core / -context / -expression / -web | 6.1.9, 6.1.11 | CVE-2025-41249 High (none); CVE-2026-41850 High (none); CVE-2024-38820, CVE-2024-38809, CVE-2025-41234, CVE-2026-41851 Moderate; CVE-2025-22233, CVE-2026-41848, CVE-2026-41852 Low |
  | httpcore5 / httpcore5-h2 | 5.2.4 | CVE-2026-54399 High (5.4.3); CVE-2026-54428 High (5.4.3) |
  | httpclient5 | 5.3.1 | CVE-2026-64607 Moderate (5.6.3) |
  | nimbus-jose-jwt (through Spring Security) | 9.37.3 | CVE-2025-53864 Moderate (9.37.4) |
  | micrometer-core (optional) | 1.13.2 | CVE-2026-40984 High (none on 1.13) |

- **Test scope only, so it does not reach apps:** logback-core 1.5.18 (CVE-2025-11226 Moderate and three Low), assertj-core 3.25.3 (CVE-2026-24400 High, XML assertions, which are not used).
- **No advisories:** pdfbox 3.0.8, angus-mail 2.0.5, jakarta-mail 2.1.5, postgresql 42.7.14 (test scope), testcontainers 2.0.5, junit 5.10.3, commons-*.
- **Without upkeep:**
  - Spring Boot 3.3.x, Spring Framework 6.1.x and Spring Security 6.3.x are past their open-source support. Many advisories above have no open-source fix on those lines.
  - Dependabot alerts and security updates are off for the repo.
- **Note:** a Spring Boot app's own Spring version wins over the platform's for the Spring artifacts. The platform's own pins (httpclient5, httpcore5) and the plain-Java uses (the setup command, non-Boot apps) keep the old versions.
- These are SEC-14 and SEC-41.

### Proposed fix tasks

Detail is in `security-findings.md` § Proposed fix tasks. The tasks that must be done before the integration release are FIX-A to FIX-I:

- **FIX-A Filesystem safety** (SEC-1, SEC-9): S–M.
- **FIX-B Erase-log integrity** (SEC-3, SEC-4, SEC-5): M.
- **FIX-C Key-service policies** (SEC-2): S.
- **FIX-D Model-governance gate** (SEC-6): M.
- **FIX-E Push-caller machine-token rule** (SEC-7): M.
- **FIX-F Integration secrets and callbacks** (SEC-8, SEC-10): M.
- **FIX-G Bundle and leak backstops** (SEC-11): M.
- **FIX-H Licensing trust root and clock** (SEC-12, SEC-13): M.
- **FIX-I Dependencies and release pipeline** (SEC-14, SEC-15): S–M.

By choice: FIX-J outbound hardening, FIX-K crypto format version 2. Noted for hosting: SEC-42, 46, 47, 50, 53, 57, 58.

Each FIX task also takes the Low and Info findings listed next to it.

## Tests asked for → where

The task asked for no tests. The one proof of concept is described above and in SEC-1. The proof steps for every other finding are in its row of `security-findings.md`.

## Files changed, commits

- `docs/work/P042.md`: the task, committed as it was.
- `docs/work/security-findings.md`: new.
- `docs/work/P042-report.md`: new.

## Decisions I had to make

1. **The dependency check uses the GitHub advisory database only**, chosen by the owner at the start. OWASP Dependency-Check was not run.
2. **SEC-1 is rated High**, although the reviewer proposed Medium. It was shown to work. It needs only write access to a share that other systems and people usually have, and it can delete the erase log, among other things.
3. **SEC-12 and SEC-13 are rated Medium but marked "commercial".** They expose no customer data, but they break stated licensing promises, so by the task's rule they come before the integration release. See question 1.
4. **SEC-27 (the usage chain without a key) is rated Low.** Nothing on the customer's side can be kept secret from the customer, so the real fix is wording plus checks on our side.
5. **Each finding is one table row, as the task asks.** Long cells are kept short, and the "what can happen" and proof cells hold the essentials only.
6. **No proof-of-concept branch.** No proof needed repo code changes, so none was created.

## Not done, and why

- **OWASP Dependency-Check:** not run (decision 1).
- **No proof run against a real Vault server or PostgreSQL** for SEC-2, SEC-33 and SEC-34. They are confirmed by reading the policy, SQL and code. A Vault policy prefix match and PostgreSQL's `pg_temp` lookup order are standard behaviour; the fix tasks should add the negative tests.

## Questions

1. **Do SEC-12 and SEC-13 (licensing) block the integration release?** Under the task's rule they do, as Medium. Recommendation: yes for SEC-13 (cheap: revoke rights plus a time floor). For SEC-12, ship the key set with the release now. The rest (development switch at build time) can go in FIX-H.
2. **Should SEC-14 move the compile baseline to Spring Boot 3.5.x?** The root POM's comment says the products run 3.3.x or 3.5.x. Recommendation: compile against the latest 3.5.x, keep a CI job on the lowest line the products still run, and ask the product teams to leave 3.3.
3. **`ScannerTest` on this laptop:** should the test skip when the antivirus blocks the download over loopback, not only when it removes the file on disk? Recommendation: yes. Extend `Eicar.staysOnDisk` into a check that also tries a loopback transfer, so local `mvn verify` is clean while CI still runs the test.
4. **Fix-task order:** recommendation:
   1. FIX-A and FIX-I first (small, and SEC-1 is the only High);
   2. then FIX-B, FIX-C, FIX-F;
   3. then FIX-D, FIX-E, FIX-G, FIX-H.
