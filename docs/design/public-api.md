# Public API (P041)

What an app may use from platform-core, and what it may not.

## The rule

- **Public to apps** is what a module's install guide (`docs/install/`), its design doc (`docs/design/`) or its line
  in `CLAUDE.md` names, and what an app must call or implement to use the module: its services and beans, its ports
  and store SPIs, its records and value types, its `*Properties` and auto-configurations, its codes
  (`PlatformCode` enums) and the exceptions an app catches.
- **Everything else is internal.** A type used only in its own package is package-private. A type that another
  package or another platform module needs, but apps do not, lives in a package whose name contains `.internal`
  (for example `com.documentplatform.notifications.webhook.internal`).
- **`internal` packages may change in any release and are not supported for apps.** They carry no compatibility
  promise, and release notes list changes to them only when a type moves in or out.
- **Test hooks are not on production classes.** A method or constructor that exists only for tests is
  package-private (tests in the same package use it), or lives in the module's testkit.
- **Testkits use public types, or the internals of the module they test.** A testkit never uses another module's
  internals.
- **Store SPIs** (interfaces a store implements: `OutboxStore`, `ValueStore`, `EraseLogStore`, …) are public, because
  the testkits and an app's own store implement them. Their javadoc says "SPI": apps call the module's service, not
  the store.

## Why not JPMS

`module-info.java` would make the rule a compile error. It does not work cleanly here: each testkit puts test support
into its production module's packages (split packages), Spring Boot's auto-configuration and binding reflect into the
modules, and the apps are not modular. So the rule is kept by package names and visibility, and checked by a build
test (`platform-error-catalogue` `PublicApiTest`):

- no install guide or design doc names a type in an `internal` package;
- a testkit uses only public types, or the internals of the module it tests.

## Known exceptions

- `com.documentplatform.notifications.secret.{SecretRef, SecretResolver, Secret}` are the platform-wide secret port
  (outbound, integration and monitoring use them, and apps implement `SecretResolver`). They live in the
  notifications module for history; moving them is a package rename and its own task.
- The licence-issuing side is our own tool: it is the module `platform-licensing-issue`, which is not in the BOM and is
  never on a customer's classpath.
