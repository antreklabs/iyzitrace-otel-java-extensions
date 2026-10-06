# Application server deployment extension for the OpenTelemetry Java agent

When several applications run in one Java application server or servlet container, the OpenTelemetry Java agent reports all of them under one `service.name`, because it has one resource per JVM. This extension adds attributes to **every span and log record** that identify the application it came from: deployment, EAR module, context root, plus a service name and namespace the application can declare itself.

```
before:  CheckoutService.checkout   code.namespace=com.example.CheckoutService  code.function=checkout
after:   CheckoutService.checkout   code.namespace=com.example.CheckoutService  code.function=checkout
                                    appserver.deployment.name=shop.ear
                                    appserver.deployment.module=shop-ejb.jar
                                    appserver.deployment.context_root=/shop
                                    appserver.deployment.service.name=shop-frontend
```

It works on **any servlet container** (generic core). **Vendor resolvers** add support for EAR modules, EJB jars, timers and MDBs on specific servers. Applications and server configuration don't change.

## Contents

1. [Supported servers](#1-supported-servers)
2. [Install](#2-install)
3. [Check that it works](#3-check-that-it-works)
4. [Configuration](#4-configuration)
5. [Attributes](#5-attributes)
6. [Optional: let each application declare its service name](#6-optional-let-each-application-declare-its-service-name)
7. [Optional: one service.name per application (collector)](#7-optional-one-servicename-per-application-collector)
8. [How values are resolved](#8-how-values-are-resolved)
9. [Limits](#9-limits)
10. [Adding a vendor resolver](#10-adding-a-vendor-resolver)
11. [Build and test](#11-build-and-test)
12. [Agent versions](#12-agent-versions)

## 1. Supported servers

The extension has two layers:

| Layer | Covers | Works on |
|---|---|---|
| **Generic core** | web applications (WARs): requests, everything inside them, and (after the first request) the application's own background threads | any Servlet 3.0+ container, `jakarta.servlet` and `javax.servlet` |
| **Vendor resolver** | additionally: exact deployment names (`orders.war`), EAR modules, standalone EJB jars, EJB timers, MDBs, work at deploy time | servers with a resolver; currently WildFly / JBoss EAP |

| Server | WARs | EAR modules, EJB jars, timers, MDBs | Status |
|---|---|---|---|
| WildFly 27 (jakarta) | ✅ | ✅ JBoss Modules resolver | **verified** |
| Tomcat 10.1 (jakarta) | ✅ | n/a (Tomcat runs only WARs) | **verified** |
| Tomcat 9 (javax) | ✅ | n/a | **verified** |
| JBoss EAP 7/8, other WildFly versions | ✅ | ✅ same resolver (same class loader naming) | expected, not tested |
| Jetty, TomEE, Payara / GlassFish, WebLogic, WebSphere / Liberty | ✅ generic core | ❌ needs a vendor resolver ([section 10](#10-adding-a-vendor-resolver)) | expected for WARs, not tested |

Also required: Java 11 or later, and **OpenTelemetry Java agent 2.0.0 or later** ([section 12](#12-agent-versions)).

## 2. Install

1. **Get the jar:** download `appserver-deployment-extension-<version>.jar` from the repository's GitHub Releases (verify it against `SHA256SUMS`), or build it ([section 11](#11-build-and-test)). The version is also in the jar manifest (`Implementation-Version`).
2. **Put it next to the agent**, e.g. `/opt/otel/extensions/appserver-deployment-extension.jar`. The server's process user must be able to read it.
3. **Add one JVM option** next to the existing `-javaagent` option:

   ```
   -Dotel.javaagent.extensions=/opt/otel/extensions/appserver-deployment-extension.jar
   ```

   | Server | Where |
   |---|---|
   | WildFly / EAP (standalone) | `bin/standalone.conf`: `JAVA_OPTS="$JAVA_OPTS -Dotel.javaagent.extensions=..."` |
   | Tomcat | `bin/setenv.sh`: `CATALINA_OPTS="$CATALINA_OPTS -Dotel.javaagent.extensions=..."` |
   | anything else | wherever the `-javaagent` option is set |

   WildFly's `standalone.sh` and Tomcat's `catalina.sh` evaluate these variables as shell code, so quote values that contain `;`, `[`, `]` or `|`.

4. **Restart the server.** Applications don't need to be redeployed or rebuilt.

If you already load other extensions, point `otel.javaagent.extensions` at a directory: every jar in it is loaded as a separate extension.

## 3. Check that it works

1. Send a request to any application.
2. Open the resulting HTTP server span in your backend. It should have `appserver.deployment.name` (e.g. `orders.war` on WildFly, `catalog` on Tomcat) and `appserver.deployment.context_root` (e.g. `/orders`).
3. Open a child span of it, e.g. a JDBC query or an outgoing HTTP call. It should have the same values.

| Symptom | Likely cause |
|---|---|
| No `appserver.deployment.*` attribute anywhere | the jar isn't loaded: check the path in `otel.javaagent.extensions` and file permissions; check the server log for agent errors at startup |
| Child spans have the attributes, HTTP server spans don't | servlet hook not applied: a Servlet 2.x application (needs 3.0+), or a jar built incorrectly ([section 11](#11-build-and-test)) |
| Background work right after startup isn't attributed (generic core) | expected until the application has served its first request ([section 8](#8-how-values-are-resolved)) |
| Attributes on spans, not on logs | the agent's log export is off (`otel.logs.exporter=none`) |

## 4. Configuration

| JVM property | Default | Effect |
|---|---|---|
| `otel.javaagent.extensions` | – | loads the extension (required) |
| `otel.instrumentation.appserver-deployment.enabled` | `true` | `false` turns the whole extension off: span and log processors and the servlet hooks |

There are no other settings. The extension works with your existing agent configuration (`otel.service.name`, exporters, sampling, ...) and doesn't change it. It needs no other agent options, such as `otel.instrumentation.methods.include`. It tags whatever spans the agent creates.

## 5. Attributes

| Attribute | Example | Present when |
|---|---|---|
| `appserver.deployment.name` | vendor resolver: `orders.war`, `shop.ear`, `reports.jar`; generic: `catalog` (context root without `/`; `ROOT` for `/`) | the code runs in an application (always set, if any attribute is set) |
| `appserver.deployment.module` | `shop-web.war`, `shop-ejb.jar` | the code runs in a module inside an EAR (vendor resolver) |
| `appserver.deployment.context_root` | `/orders` | inside an HTTP request that reached a servlet or filter of that application; not for timers, MDBs, background threads or deploy-time work |
| `appserver.deployment.service.name` | `order-service` | the application declares a service name ([section 6](#6-optional-let-each-application-declare-its-service-name)) |
| `appserver.deployment.service.namespace` | `commerce` | the application declares a namespace |

All five are set on **spans** and on **log records**, with the same rules. **Metrics are not tagged** ([section 9](#9-limits)).

How many are set:

| Situation | Count | Attributes |
|---|---|---|
| outside any application (404, server internals), or extension disabled | 0 | – |
| code outside a request (timer, background thread, startup), nothing declared | 1 | `name` |
| HTTP request, nothing declared | 2 | `name`, `context_root` |
| HTTP request, name and namespace declared | 4 | `name`, `context_root`, `service.name`, `service.namespace` |
| HTTP request in an EAR module, name and namespace declared | 5 | all |

**The resource isn't changed.** `service.name` stays the server-wide value from `otel.service.name`, because the agent has one resource per JVM. The declared values are deliberately emitted as `appserver.deployment.service.*`, not as a span attribute named `service.name`, which backends handle inconsistently. To make them the real `service.name`, see [section 7](#7-optional-one-servicename-per-application-collector).

Why `appserver.`: there's no OTel semantic convention for this, and semantic conventions use `app.*` for mobile and browser applications.

## 6. Optional: let each application declare its service name

Add `src/main/resources/META-INF/microprofile-config.properties` to the application:

```properties
otel.service.name=order-service
otel.resource.attributes=service.namespace=commerce
```

In a WAR this ends up in `WEB-INF/classes/META-INF/`, and in an EJB jar in `META-INF/`. It's the same file and format that MicroProfile Telemetry reads (e.g. WildFly 28+), so the declaration keeps working with server-provided telemetry. The extension reads only these keys; the file doesn't affect anything else.

| You write | Extension sets |
|---|---|
| `otel.service.name=a` | `appserver.deployment.service.name=a` |
| `otel.resource.attributes=service.name=a` | `appserver.deployment.service.name=a` |
| both, with different values | `otel.service.name` wins |
| `otel.resource.attributes=service.namespace=b,team=x` | `appserver.deployment.service.namespace=b` (other keys are ignored) |
| no file, or neither key | nothing |

**Where it's read:**
- **Any servlet container:** the WAR's `WEB-INF/classes/META-INF/microprofile-config.properties`, through the `ServletContext`, on the application's first request.
- **With a vendor resolver** (WildFly / EAP), also for EJB jars and EAR modules, and outside requests. A deployment can see several such files, so the resolver picks in this order:
  1. the module's own classes;
  2. the module's own libraries (`WEB-INF/lib`);
  3. any other file inside the same top-level deployment, e.g. a jar in `ear/lib`, which declares one name for the whole EAR.

  Files outside the deployment are ignored, and the file is read again after a redeploy.

**EAR modules.** A module that declares nothing inherits the declaration from its parent span, if that parent is in the same EAR. Example: `shop-web.war` declares `shop-frontend` and calls an EJB in `shop-ejb.jar`; the EJB spans get `shop-frontend` too. This only works inside a request. For timers or MDBs in an undeclared module, declare in that module or in `ear/lib`.

## 7. Optional: one service.name per application (collector)

The agent can't emit a different resource per application, but an OpenTelemetry Collector (contrib distribution) can. These processors split the spans by application and set `service.name` and `service.namespace`:

```yaml
processors:
  # Move the deployment attributes from spans to their resource; spans with different values
  # end up under different resources.
  groupbyattrs/deployment:
    keys:
      - appserver.deployment.name
      - appserver.deployment.context_root
      - appserver.deployment.service.name
      - appserver.deployment.service.namespace

  # Later statements override earlier ones.
  transform/deployment-as-service:
    error_mode: ignore
    trace_statements:
      - context: resource
        statements:
          - set(attributes["service.namespace"], attributes["service.name"]) where attributes["appserver.deployment.name"] != nil
          - set(attributes["service.name"], attributes["appserver.deployment.name"]) where attributes["appserver.deployment.name"] != nil
          - set(attributes["service.name"], attributes["appserver.deployment.context_root"]) where attributes["appserver.deployment.context_root"] != nil and attributes["appserver.deployment.context_root"] != "/"
          - replace_pattern(attributes["service.name"], "^/", "") where attributes["appserver.deployment.context_root"] != nil and attributes["appserver.deployment.context_root"] != "/"
          - set(attributes["service.name"], attributes["appserver.deployment.service.name"]) where attributes["appserver.deployment.service.name"] != nil
          - set(attributes["service.namespace"], attributes["appserver.deployment.service.namespace"]) where attributes["appserver.deployment.service.namespace"] != nil

service:
  pipelines:
    traces:
      receivers: [otlp]
      processors: [groupbyattrs/deployment, transform/deployment-as-service, batch]
      exporters: [your-exporter]
```

Resulting resource attributes:

| Attribute | First match wins | Example |
|---|---|---|
| `service.name` | 1. `appserver.deployment.service.name` | `order-service` |
| | 2. `appserver.deployment.context_root` without the leading `/` (not for `/`) | `inventory` |
| | 3. `appserver.deployment.name` | `inventory.war` |
| | 4. unchanged | the server-wide name |
| `service.namespace` | 1. `appserver.deployment.service.namespace` | `commerce` |
| | 2. the server-wide `service.name` | `wildfly-prod-1` |

Rule 3 applies only to spans outside a request in applications that declare nothing. With a vendor resolver, such an application can show up twice, e.g. `inventory` and `inventory.war`. Declaring a name avoids this. With the generic core, the name and the context root give the same value (`catalog`).

You don't have to use the collector. You can also filter or group by `appserver.deployment.name` or `appserver.deployment.service.name` directly in your backend.

## 8. How values are resolved

| Input | Gives | Layer |
|---|---|---|
| `ServletContext` when a request enters a servlet or filter: context path, `WEB-INF/classes/META-INF/microprofile-config.properties` | `name` (generic), `context_root`, declaration | generic core |
| The thread's context class loader, recognised by a vendor resolver (WildFly: `deployment.<name>` / `deployment.<ear>.<module>`) | `name`, `module`, declaration | vendor resolver |
| The thread's context class loader, **learned** from earlier requests | `name`, declaration | generic core |
| Attributes of the parent span in the same JVM | anything the current thread can't know | both |

**Rules, for each new span or log record:**

1. **A vendor resolver that recognises the thread's class loader wins.** It reflects where the code runs *now*, so a call into an EJB in another module is attributed to that module.
2. **Otherwise, with an attributed parent span in this JVM, everything is copied from it.**
3. **Otherwise (no parent: timers, background threads), a learned class loader is used.**
4. **Same application as the parent:** `context_root` is copied from the parent. The declaration is copied too, if the current module declares none.
5. **A remote parent** (a request arriving over HTTP, even from the same server) is never inherited from. Each application's server span is tagged by its own servlet hook.

**The servlet hook.** The agent starts the HTTP server span in the container *before* the request is routed, when nothing identifies the application. Hooks on `Servlet#service` and `Filter#doFilter` (jakarta and javax) tag that span once the request reaches the application. The filter hook covers frameworks that answer from a filter alone (Struts 2, Wicket, ...). On the first request per application, the hook reads the context path and the declaration file and caches them.

**Learning (generic core).** When a span starts as a direct child of a tagged HTTP server span, the thread's class loader at that moment is the application's. The extension remembers that, so later spans on that class loader without a parent (e.g. a scheduled task the application started) are attributed too. Safety rules:
- The system class loader, its parents, and the agent's own loaders are never learned.
- A class loader seen with two different applications is never used.
- Parents of learned class loaders (shared loaders such as Tomcat's `common`) are never used.
- Learning is switched off once a vendor resolver has recognised any deployment, because that server is covered more precisely.

**Overhead:** one map lookup per span and per log record. The declaration file is read once per application.

## 9. Limits

| Limit | Effect | Workaround |
|---|---|---|
| Requests that never reach a servlet or filter: 404 for an unknown context root, container-only handlers, HTTP/2 `h2c` upgrade exchanges (status 101; Java's `HttpClient` tries this by default) | span has no attributes | none needed: they belong to no application |
| Generic core: background work **before** an application's first request (startup listeners, the first runs of a scheduler) | not attributed | send a request after startup (e.g. a health check), or add a vendor resolver |
| Generic core: EAR modules, standalone EJB jars, MDBs | not attributed, unless called inside a request (then inherited) | add a vendor resolver |
| Metrics | not tagged; the SDK has no hook to add attributes to metric recordings | group by `http.route`, which starts with the context root |
| Undeclared EAR module running outside a request (timer, MDB) | no inherited declaration | declare in that module or in `ear/lib` |
| Threads with no recognised or learned class loader and no parent span | no attributes | — |
| Servlet 2.x applications | servlet hook skipped (needs `ServletRequest#getServletContext`, Servlet 3.0) | — |
| Agent versions newer than the tested range | the agent's extension API is not stable; a newer agent could break the servlet hooks | [section 12](#12-agent-versions) |

## 10. Adding a vendor resolver

A vendor resolver tells the extension which deployment a class loader belongs to. That gives a server full coverage: EAR modules, EJB jars, timers, MDBs, and startup work.

1. Find out how the server's deployment class loaders identify themselves. Start a server with two deployments, set a breakpoint or log `Thread.currentThread().getContextClassLoader()` inside each (`getName()`, `toString()`, class name), and check whether an EJB call switches the context class loader.
2. Implement [`VendorResolver`](src/main/java/com/iyzitrace/otel/appserver/shared/VendorResolver.java): `DeploymentInfo resolve(ClassLoader)`. It must return `null` for loaders it doesn't recognise and cache its results. Use only JDK APIs if possible; no vendor jars at compile time. [`JBossModulesResolver`](src/main/java/com/iyzitrace/otel/appserver/shared/JBossModulesResolver.java) is the reference.
3. Register it in [`VendorResolvers`](src/main/java/com/iyzitrace/otel/appserver/shared/VendorResolvers.java), and add the class (and any nested classes) to `getAdditionalHelperClassNames()` in [`ServletInstrumentationModule`](src/main/java/com/iyzitrace/otel/appserver/ServletInstrumentationModule.java). `HelperClassNamesTest` fails the build if you forget.
4. Add unit tests with class loaders named or shaped like the server's (see `JBossModulesResolverTest`), and verify on the real server.

## 11. Build and test

The project is a Maven module of the repository. From the repository root, with Docker and no local Maven needed:

```sh
docker run --rm -v "$PWD":/src -w /src maven:3.9.9-eclipse-temurin-17 \
  mvn -B -ntp -pl appserver-deployment package
# -> appserver-deployment/target/appserver-deployment-extension.jar
```

Add `-Drevision=1.2.3` to stamp a version into the jar manifest. Releases are built by CI from a `vX.Y.Z` tag; see the [root README](../README.md#releases-and-versioning).

The build runs 32 unit tests:
- the JBoss Modules resolver: names, declarations, file precedence, redeploys;
- declaration parsing;
- generic naming;
- the learning safety rules;
- the span processor end to end on a real SDK: vendor resolution, inheritance, learning, vendor mode switching learning off, EAR inheritance, remote parents;
- a guard that every helper class is registered with the agent. The agent copies these classes into each application; a missing one would make the servlet hooks fail silently, so the build fails instead.

All dependencies are `provided`: the agent supplies them at runtime, and the jar contains only the extension's own classes.

## 12. Agent versions

**Minimum: OpenTelemetry Java agent 2.0.0.** One jar is meant to work with every agent from 2.0.0 up. You don't need a build per agent version.

What has been checked:

| Agent | Bundled SDK | Compiles against its API | Unit tests (32) | Running on real servers |
|---|---|---|---|---|
| 2.0.0 (Dec 2023, first 2.x) | 1.34.1 | ✅ | ✅ | not yet |
| 2.10.0 | 1.44.1 | ✅ | ✅ | not yet |
| 2.21.0 | 1.55.0 | ✅ | ✅ | ✅ WildFly 27, Tomcat 10.1, Tomcat 9 |
| 2.32.0 (Oct 2026, latest) | 1.66.0 | ✅ | ✅ | not yet |

"Compiles against its API" means the source uses nothing that agent lacks. Agent 1.x is not supported; it has been end-of-life since August 2024.

**Why a minimum and not "any version".** The extension uses two kinds of agent API:

| Part | API | Stability |
|---|---|---|
| span and log processors, auto-configuration | OpenTelemetry SDK interfaces | stable |
| servlet and filter hooks | the agent's extension API (`InstrumentationModule`, matchers), published as `-alpha` | **no compatibility guarantee** between agent releases |

So "2.0.0 or later" really means "2.0.0 up to the newest version tested". If a future agent changes the extension API, the processors keep working (child spans and logs still get attributes), but the servlet hooks may not load: HTTP server spans then lack the attributes. In the worst case the agent logs an error at startup and loads no part of the extension.

**Which version the build compiles against.** It's set in the root [pom.xml](../pom.xml). Currently it's 2.21.0, the version the lab runs:

| Property | Current | Meaning |
|---|---|---|
| `otel.agent.version` | `2.21.0` | agent extension API used at compile time |
| `otel.sdk.version` | `1.55.0` | SDK used at compile time; the one bundled in that agent (`io/opentelemetry/javaagent/shaded/io/opentelemetry/api/version.properties` inside the agent jar) |

These are compile-time settings only. They don't have to match the agent your servers run. To check that the source still only uses APIs present in another agent version, e.g. the minimum:

```sh
docker run --rm -v "$PWD":/src -w /src maven:3.9.9-eclipse-temurin-17 \
  mvn -B -ntp -pl appserver-deployment package -Dotel.agent.version=2.0.0 -Dotel.sdk.version=1.34.1
```

**When you upgrade the agent (or run one outside the tested range):**

1. Compile and test against the new version, as above.
2. Run it once on a real server and check that HTTP server spans **and** child spans carry `appserver.deployment.name` ([section 3](#3-check-that-it-works)). In the [lab](../lab/README.md): set `OTEL_JAVA_AGENT_VERSION` in `lab/.env`, run `docker compose up -d --build`, then `./scripts/verify.sh` (both from `lab/`).
3. Add the version to the table above.
