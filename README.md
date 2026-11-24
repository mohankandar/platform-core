# platform-core

Shared platform libraries for document products: identity, licensing, notifications, case fields, encryption and
keys, the erase log, configurable integrations and the setup command. Each module is a plain Java library (some with
a Spring Boot wiring module) that an app puts on its classpath. platform-core is not a server and is never deployed on
its own.

## Using it

**Needs Spring Boot 4.2 and Java 21** (`docs/design/platform-baseline.md`). Apps use **released versions only**, from GitHub Packages:

```xml
<repositories>
  <repository>
    <id>github-platform-core</id>
    <url>https://maven.pkg.github.com/mohankandar/platform-core</url>
  </repository>
</repositories>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.documentplatform.platform</groupId>
      <artifactId>platform-bom</artifactId>
      <version>1.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Reading packages needs a token with `read:packages` in your own `~/.m2/settings.xml` (server id
`github-platform-core`), never in a repository. Release notes: `docs/releases/`.

## Modules

The module list, with what each one holds, is in [CLAUDE.md](CLAUDE.md). Install guides are in `docs/install/`,
designs in `docs/design/`.

## Building

```
mvn verify
```

Java 21, Spring Boot 4.2, Maven 3.9+. Releases are tags `platform-core-vX.Y.Z`; the release workflow publishes them. To build a tag
offline into a separate local repository: `scripts/build-release.ps1 -Tag platform-core-vX.Y.Z -Repository <path>`
(or `scripts/build-release.sh`).

## History

Releases before platform-core were the identity library alone (tags `v0.2.1` and `v0.3.0`); they are kept as they
are and are not published here.
