# platform-core 0.8.0

**Requires Spring Boot 4.2 and Java 21.** Boot 3.x, 4.0 and 4.1 are not supported. No schema change and no behaviour change
against 0.7.0. Every module has the same version. Import the BOM:

```xml
<dependency>
  <groupId>com.documentplatform.platform</groupId>
  <artifactId>platform-bom</artifactId>
  <version>0.8.0</version>
  <type>pom</type>
  <scope>import</scope>
</dependency>
```

Published to GitHub Packages, `https://maven.pkg.github.com/mohankandar/platform-core`.

**No other product's name or behaviour in code.**

## Versions

| | 0.7.0 | 0.8.0 |
|---|---|---|
| Spring Boot | 3.3.2 | 4.2.0 |
| Spring Framework | 6.1 | 7.1.0 |
| Spring Security | 6.3.1 | 7.2.0 |
| Micrometer (optional) | 1.13.2 | 1.18.0 |
| Nimbus JOSE + JWT | 9.37.4 | 10.9.1, the one Spring Security brings; no pin of ours any more |
| Servlet API (optional) | 6.0 | 6.1 |
| JUnit (tests) | 5.10.3 | 6.1.3 |

The versions are pinned to what Boot 4.2 manages and are never imported through `platform-bom`: your app's Boot 4.2.x patch
wins. Why and until when: `docs/design/platform-baseline.md`.

## Migration checklist for apps

- [ ] Move the app to Spring Boot 4.2.x and Java 21 first; platform-core 0.7.0 was built for Boot 3.3 and is
      not supported on it.
- [ ] If the app came from 0.6.x, do everything in the 0.7.0 notes (schema steps, licence keys, callbacks) as well.
- [ ] Health: our health views use `org.springframework.boot.health.contributor.*` (module `spring-boot-health`, which
      Actuator brings). Code or tests that used our health beans as `org.springframework.boot.actuate.health.*` change the
      import.
- [ ] Health tests: add Boot's `HealthContributorRegistryAutoConfiguration` beside the endpoint's auto-configuration.
- [ ] Auto-configuration order: an app that names `DataSourceAutoConfiguration` by text uses
      `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`. Boot ignores a missing name silently.
- [ ] Remove overrides of Nimbus, httpcore5 or httpclient5 that only served 0.6.x; Boot 4.2 manages httpclient5 5.6.5 and
      httpcore5 5.4.4.
- [ ] Security: the platform configures no filter chain of its own; the app's own configuration uses the lambda DSL, as
      Spring Security 7 requires.
- [ ] No platform property was renamed.

## Advisories (SEC-14)

GitHub advisory database, checked 2026-11-23 over every resolved third-party artifact in every scope: **no open advisory**.
In 0.7.0 the Spring 6.1 / Boot 3.3 / Security 6.3 line had open Critical and High ones without an open-source fix.

## Database schemas

Unchanged from 0.7.0: identity 5, licensing 2, fields 2, erase log 3, integration 3, model governance 5, notifications
unchanged.
