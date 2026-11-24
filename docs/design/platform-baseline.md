# Platform baseline (P046)

| What | Line | Compiled and tested with |
|---|---|---|
| Java | 21 | 21 |
| Spring Boot | 4.2 | 4.2.0 |
| Spring Framework | 7.1 (managed by Boot 4.2) | 7.1.0 |
| Spring Security | 7.2 (managed by Boot 4.2) | 7.2.0 |
| Micrometer | as Boot 4.2 manages | 1.18.0 |

- **Minimum for apps:** Spring Boot 4.2 and Java 21. Boot 3.x and 4.0 / 4.1 are not supported.
- **Why 4.2:** the 3.x line no longer gets open-source fixes, so its advisories stay open (SEC-14). All products move to the
  same line, so one set of released jars serves them.
- **Review by December 31, 2027,** when open-source support of 4.2 ends: decide the next line before that date.
- **Pinned, not imported.** The root POM names the versions as properties, equal to what Boot 4.2 manages. Importing Boot's
  bill of materials there would bring it into every app through `platform-bom`, which inherits the root; the app's own
  Boot 4.2.x patch must win.
- **The plain Java modules have no Spring dependency.** Only the `-spring` modules use it.
- **Boot's split modules:** the health classes are in `spring-boot-health` (optional in every `-spring` module that has a
  health view; every app with Actuator has it), `DataSourceAutoConfiguration` is in `spring-boot-jdbc` (named by text only).
