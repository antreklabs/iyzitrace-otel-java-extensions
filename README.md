# iyzitrace OpenTelemetry Java extensions

Extensions for the [OpenTelemetry Java agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation). They're loaded with `-Dotel.javaagent.extensions=...` and work with any OpenTelemetry backend, not only iyzitrace. This is not an official OpenTelemetry project.

## Extensions

| Extension | What it does | Docs |
|---|---|---|
| **appserver-deployment** | When several applications run in one application server or servlet container, attributes every span and log record to its application: deployment, EAR module, context root, and an app-declared service name. Generic for any Servlet 3.0+ container (jakarta and javax); full EAR/EJB support on WildFly / JBoss EAP | [appserver-deployment/README.md](appserver-deployment/README.md) |

Requires OpenTelemetry Java agent 2.0.0 or later, and Java 11 or later.

## Guides

- [WildFly metrics](docs/wildfly-metrics.md): every metric source for WildFly (JVM, HTTP, WildFly statistics, JMX), set up step by step with the agent and the collector.

## Lab

[lab/](lab/README.md) runs WildFly 27, Tomcat 10.1 and Tomcat 9 with seven sample applications, an OpenTelemetry Collector and Jaeger in Docker Compose. It's used to verify the extensions on real servers.

```sh
cd lab
cp .env.example .env
docker compose up -d --build
./scripts/verify.sh
```

## Build

Docker is the only requirement; no local Maven or JDK is needed. From the repository root:

```sh
docker run --rm -v "$PWD":/src -w /src maven:3.9.9-eclipse-temurin-17 mvn -B -ntp package
# extension: appserver-deployment/target/appserver-deployment-extension.jar
```

Released jars are on the [Releases page](../../releases); see [Releases and versioning](#releases-and-versioning).

`-pl appserver-deployment` builds only the extension.

## Releases and versioning

Versions follow [Semantic Versioning](https://semver.org): `MAJOR.MINOR.PATCH`.

- **MAJOR**: incompatible change (attribute names or semantics change, minimum Java or agent version raised).
- **MINOR**: new backwards-compatible capability (new attribute, new vendor resolver).
- **PATCH**: bug fix only.

The git tag is the single source of truth. Pushing a tag `vX.Y.Z` runs [.github/workflows/release.yml](.github/workflows/release.yml), which builds with `-Drevision=X.Y.Z`, runs all tests, and publishes a GitHub release with:

- `appserver-deployment-extension-X.Y.Z.jar` (the jar's manifest carries `Implementation-Version: X.Y.Z`)
- `SHA256SUMS`
- auto-generated release notes (merged PRs and commits since the previous tag)

The project starts at `0.1.0`: while the major version is 0, minor bumps may include breaking changes. Move to `1.0.0` once the attribute names are stable.

To release (from an up-to-date `main` with a green CI run):

```sh
git tag -a v0.1.0 -m "v0.1.0"
git push origin v0.1.0
```

A tag with a hyphen suffix (`v1.3.0-rc.1`, `v1.3.0-beta.2`) is published as a **pre-release**. A mistaken release is fixed by deleting the GitHub release and the tag (`git push origin :refs/tags/v1.2.3`), then tagging again; never reuse a version that users may have downloaded.

The `<revision>` property in the root [pom.xml](pom.xml) is only the default for local builds (e.g. `0.1.0`). All modules inherit it through `${revision}`, so there is no version to edit in any module. Bump it after a release if you want local builds to reflect the latest version; releases don't depend on it. Pull requests and pushes to `main` are built and tested by [.github/workflows/ci.yml](.github/workflows/ci.yml).

To try a version locally:

```sh
docker run --rm -v "$PWD":/src -w /src maven:3.9.9-eclipse-temurin-17 mvn -B -ntp -Drevision=1.2.3 package
```

## Layout

```
appserver-deployment/   OpenTelemetry Java agent extension (Maven module)
docs/                   guides that aren't specific to one extension
lab/                    WildFly + Tomcat lab for verifying the extensions
.github/workflows/      CI (build and test) and release (tag-triggered)
pom.xml                 Maven parent: modules and dependency versions
```

## License

[Apache License 2.0](LICENSE)
