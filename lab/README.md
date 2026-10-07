# Multi-deployment OpenTelemetry lab: WildFly 27, Tomcat 10.1, Tomcat 9

Three servers, seven applications, one OpenTelemetry Java agent per server (**2.21.0** by default; set `OTEL_JAVA_AGENT_VERSION` in `.env` to run another one). It demonstrates the application server deployment extension, which attributes every span and log record to the application it came from:

- on **WildFly** through the vendor resolver;
- on **Tomcat** through the generic core, with both `jakarta.servlet` and `javax.servlet`.

> **Setting up the extension on your own server?** See **[../appserver-deployment/README.md](../appserver-deployment/README.md)**. It covers supported servers, installation, configuration, the attributes, app-declared service names, the collector setup, limits and adding vendor resolvers. This file only describes the lab.

## Contents

1. [Quick start](#1-quick-start)
2. [What runs](#2-what-runs)
3. [The applications](#3-the-applications)
4. [Server settings (`JAVA_OPTS`)](#4-server-settings-java_opts)
5. [Collector pipelines](#5-collector-pipelines)
6. [Cases, with real output](#6-cases-with-real-output)
7. [Verifying](#7-verifying)
8. [Layout](#8-layout)

## 1. Quick start

```sh
cd lab
cp .env.example .env          # set IYZITRACE_API_KEY in .env (never in .env.example)
docker compose up -d --build
./scripts/verify.sh           # needs curl + jq; give the load generator ~30 s first
```

| What | Where |
|---|---|
| WildFly apps | http://localhost:8090/orders/api/orders · `/inventory/api/stock/apple` · `/inventory/health` · `/shop/api/checkout/apple` |
| Tomcat 10.1 apps | http://localhost:8091/catalog/items/apple · `/catalog/status` · `/pricing/price?item=apple` |
| Tomcat 9 app | http://localhost:8092/legacy/hello · `/legacy/ping` |
| Jaeger (one service per application) | http://localhost:16686 |
| iyzitrace (traces: one service per application, like Jaeger) | your iyzitrace UI |
| Log records with attributes | `docker compose logs otel-collector \| grep -A14 'LogRecord #'` |
| Reproduce the original problem | `DEPLOYMENT_EXTENSION_ENABLED=false docker compose up -d --force-recreate` |
| Stop | `docker compose down` |

## 2. What runs

| Container | Purpose |
|---|---|
| `wildfly` | WildFly 27.0.1 + agent + extension: `orders.war`, `inventory.war`, `shop.ear`, `reports.jar` |
| `tomcat10` | Tomcat 10.1 (jakarta) + agent + extension: `catalog.war`, `pricing.war` |
| `tomcat9` | Tomcat 9 (javax) + agent + extension: `legacy.war` |
| `otel-collector` | receives OTLP from all three agents and scrapes WildFly's `/metrics` endpoint; sends traces renamed per application to Jaeger and iyzitrace, metrics and logs unchanged to iyzitrace ([section 5](#5-collector-pipelines)) |
| `jaeger` | local trace UI |
| `loadgen` | [loadgen/traffic.sh](loadgen/traffic.sh): steady traffic to all applications. Every 7th round it also triggers errors: 500/502 on WildFly and Tomcat, and 404s for `/no-such-app/` on both. These errors and their stack traces in the server logs are intentional |

All images are built by one [Dockerfile](Dockerfile) with targets `wildfly`, `tomcat10` and `tomcat9`. They share a Maven build stage (all applications), an agent download stage, and an extension download stage. The extension is the **released jar** from GitHub Releases, checked against the release's `SHA256SUMS`; `EXTENSION_VERSION` (build arg, default `0.1.0`) picks the release. The build context is the repository root, because the applications are modules of the root [pom.xml](../pom.xml).

`.env` settings (copy from [.env.example](.env.example)):

| Variable | Default | Purpose |
|---|---|---|
| `IYZITRACE_API_KEY` | – | collector → iyzitrace. **Only in `.env`**, which is gitignored |
| `OTEL_JAVA_AGENT_VERSION` | `2.21.0` | agent the three servers run. Independent of the version the extension is compiled against (`otel.agent.version` in the root [pom.xml](../pom.xml)); the extension supports agent 2.0.0 and later ([extension README, section 12](../appserver-deployment/README.md#12-agent-versions)) |
| `IYZITRACE_OTLP_ENDPOINT` | `http://host.docker.internal:80/ingest/otlp` | iyzitrace ingest |
| `AGENT_OTLP_ENDPOINT` / `AGENT_OTLP_HEADERS` | collector / empty | point the agents straight at iyzitrace to skip the collector |
| `DEPLOYMENT_EXTENSION_ENABLED` | `true` | A/B switch for the extension, on all three servers |
| `OTEL_SERVICE_NAME` | `wildfly-27-lab` | WildFly's server-wide `service.name` (Tomcats: `tomcat-10-lab`, `tomcat-9-lab`) |
| `WILDFLY_HTTP_PORT`, `TOMCAT10_HTTP_PORT`, `TOMCAT9_HTTP_PORT` | `8090`, `8091`, `8092` | host ports (8080 is often taken) |
| `WILDFLY_MANAGEMENT_PORT`, `JAEGER_UI_PORT` | `9990`, `16686` | host ports |

A change in `.env` takes effect with `docker compose up -d`, which recreates the affected containers.

## 3. The applications

| Server | Application | Type | Declares (`microprofile-config.properties`) | What it exercises |
|---|---|---|---|---|
| WildFly | `orders.war` (`/orders`) | Jakarta REST, CDI, startup EJB | `order-service` / `commerce` | HTTP server and client, JDBC (H2 `ExampleDS`), deploy-time SQL, logs |
| WildFly | `inventory.war` (`/inventory`) | Jakarta REST + plain servlet | nothing | fallback naming, random latency, `sku=broken` → 500 |
| WildFly | `shop.ear` (`/shop`) | EAR = `shop-web.war` + `shop-ejb.jar` | `shop-web.war`: `shop-frontend` (no namespace) | EAR modules, local EJB calls, one trace across three deployments |
| WildFly | `reports.jar` | standalone EJB jar | `reporting-job` / `back-office` | `@Schedule` timer every 15 s with JDBC |
| Tomcat 10.1 | `catalog.war` (`/catalog`) | plain servlets (jakarta) | `catalog-service` / `commerce` | HTTP client → pricing, a **filter-only** endpoint (`/status`), a **background scheduler** started by a `ServletContextListener` |
| Tomcat 10.1 | `pricing.war` (`/pricing`) | plain servlet (jakarta) | nothing | generic naming, `item=broken` → 500 |
| Tomcat 9 | `legacy.war` (`/legacy`) | servlet + filter-only endpoint (**javax**) | nothing | the `javax.servlet` hooks |

The declarations are in `apps/**/src/main/resources/META-INF/microprofile-config.properties`.

## 4. Server settings (`JAVA_OPTS`)

Every server setting is a JVM option in `JAVA_OPTS` in [docker-compose.yml](docker-compose.yml). WildFly's options are grouped by purpose, in this order. **Use** says what a server of your own needs:

- **required**: without it, nothing (or nothing useful) is exported;
- **default**: the agent's default value, set explicitly so the lab doesn't depend on it; can be left out;
- **optional**: adds data; the extension doesn't need it;
- **lab**: specific to this lab or its environment.

### 1. JVM defaults

| Option | Use | Purpose |
|---|---|---|
| `-Xms128m -Xmx768m -XX:MetaspaceSize=96M -XX:MaxMetaspaceSize=384m` | lab | heap and metaspace sizes. Setting `JAVA_OPTS` replaces the defaults from WildFly's `standalone.conf`, so they're restored here, with more memory for the agent |
| `-Djava.net.preferIPv4Stack=true -Djava.awt.headless=true -Djboss.modules.system.pkgs=org.jboss.byteman` | lab | the rest of `standalone.conf`'s defaults. On your own server, append to `JAVA_OPTS` in `standalone.conf` instead (`JAVA_OPTS="$JAVA_OPTS ..."`) and the defaults stay |

### 2. Proxy

| Option | Use | Purpose |
|---|---|---|
| `-Dhttp.nonProxyHosts="localhost\|127.*\|172.30.169.137"` | lab | hosts the JVM reaches directly when an HTTP proxy is configured. Only matters in an environment with a proxy; harmless otherwise |

### 3. WildFly metrics

| Option | Use | Purpose |
|---|---|---|
| `-Dwildfly.statistics-enabled=true` | optional | WildFly's runtime statistics in every subsystem (undertow, datasources, transactions, EJB, ...). Without it, most `wildfly_*` metrics read 0 or freeze ([WildFly metrics, step 4](../docs/wildfly-metrics.md#step-4-turn-on-wildfly-statistics)). Not an agent option: WildFly reads it |

### 4. Agent

| Option | Use | Purpose |
|---|---|---|
| `-javaagent:/opt/opentelemetry-javaagent.jar` | **required** | loads the OpenTelemetry Java agent. Everything below except groups 1–3 is read by the agent |

### 5. Export

| Option | Use | Purpose |
|---|---|---|
| `-Dotel.service.name=${OTEL_SERVICE_NAME:-wildfly-27-lab}` | **required** | the server-wide `service.name` on every span, metric and log record. Without it: `unknown_service:java` |
| `-Dotel.exporter.otlp.endpoint=${AGENT_OTLP_ENDPOINT:-http://otel-collector:4318}` | **required** | where the agent sends data: the collector here. Default `http://localhost:4318` |
| `-Dotel.exporter.otlp.protocol=http/protobuf` | default | OTLP over HTTP; matches port 4318. `grpc` would need port 4317 |
| `-Dotel.exporter.otlp.headers=${AGENT_OTLP_HEADERS:-}` | optional | extra request headers, e.g. an API key when the agent sends straight to a backend. Empty for the collector |
| `-Dotel.traces.exporter=otlp`, `-Dotel.metrics.exporter=otlp`, `-Dotel.logs.exporter=otlp` | default | send all three signals with OTLP. `none` switches one off |

### 6. Extension

| Option | Use | Purpose |
|---|---|---|
| `-Dotel.javaagent.extensions=/opt/otel/extensions/appserver-deployment-extension.jar` | **required** | loads the extension. The only option the extension needs ([extension README, section 2](../appserver-deployment/README.md#2-install)) |
| `-Dotel.instrumentation.appserver-deployment.enabled=${DEPLOYMENT_EXTENSION_ENABLED:-true}` | lab | the lab's A/B switch (`DEPLOYMENT_EXTENSION_ENABLED` in `.env`). Default `true`; leave it out on your own server |

### 7. Agent metrics

| Option | Use | Purpose |
|---|---|---|
| `-Dotel.metric.export.interval=15000` | optional | push metrics every 15 s instead of the default 60 s; same as the collector's scrape interval for WildFly's `/metrics` |
| `-Dotel.instrumentation.runtime-telemetry.emit-experimental-telemetry=true` | optional | extra JVM metrics on top of the default `jvm.*` set: buffer pools, file descriptors, system CPU ([WildFly metrics, step 2](../docs/wildfly-metrics.md#step-2-jvm-metrics)) |

### 8. More spans

| Option | Use | Purpose |
|---|---|---|
| `-Dotel.instrumentation.common.experimental.controller-telemetry.enabled=true` | optional | one extra span per request for the JAX-RS method that handled it (`OrderResource.create`, ...). **Off by default** in agent 2.x; without it only the HTTP server span names the request (`POST /orders/api/orders`). The cases in [section 6](#6-cases-with-real-output) show these spans |
| `"-Dotel.instrumentation.methods.include=...CheckoutService[checkout];...PricingService[priceInCents];...ReportJob[run]"` | lab | a span for each listed method. Agent 2.21.0 has no EJB instrumentation, so EJB and timer methods produce no spans without it. The lab uses it to show the original problem (spans with only `code.*` attributes) and the timer case. On your own server, list the business methods you want to see |

`standalone.sh` evaluates `JAVA_OPTS` as shell code, hence the quotes around `methods.include` (contains `;`, `[`, `]`) and `nonProxyHosts` (contains `|`).

**Minimum for your own WildFly:** group 4, `otel.service.name` and `otel.exporter.otlp.endpoint` from group 5, and group 6's first option. Everything else adds data or is specific to the lab.

The Tomcats (currently commented out in `docker-compose.yml`) use group 4, group 5 without the `*.exporter` options, group 6 and the export interval. Tomcat's `catalina.sh` adds `JAVA_OPTS` to its own defaults rather than replacing them, so they don't need group 1.

## 5. Collector pipelines

[otel-collector.yaml](otel-collector.yaml) receives everything once and sends it down these pipelines:

| Pipeline | Destination | What happens |
|---|---|---|
| `traces/jaeger` | Jaeger | **renamed**: `groupbyattrs` + `transform` give each application its own `service.name` and `service.namespace` (rules: [extension README, section 7](../appserver-deployment/README.md#7-optional-one-servicename-per-application-collector)) |
| `traces/iyzitrace` | iyzitrace (+ `debug` output) | **renamed**, same processors as Jaeger |
| `metrics` | iyzitrace (+ `debug` output) | **unchanged**: OTLP from the agents plus WildFly's `/metrics` scraped by `prometheus/wildfly`; one `service.name` per server |
| `logs` | iyzitrace (+ `debug` output) | **unchanged**: one `service.name` per server; log records carry the `appserver.deployment.*` attributes |

So both **Jaeger** and **iyzitrace** list the traces under `order-service`, `inventory`, `shop-frontend`, `reporting-job`, `catalog-service`, `pricing` and `legacy`. Metrics and logs in iyzitrace stay under `wildfly-27-lab`, `tomcat-10-lab` and `tomcat-9-lab`. For logs, filter or group by `appserver.deployment.*`.

To send iyzitrace the traces exactly as the agents emit them (one service per server, the same as an agent exporting directly), remove `groupbyattrs/deployment, transform/deployment-as-service` from `traces/iyzitrace`.

### WildFly metrics

The `metrics` pipeline gets three sources from WildFly: the agent's JVM (1) and HTTP (2) metrics, and WildFly's own `/metrics` endpoint, which the collector scrapes (`prometheus/wildfly`, source 3). The agent's WildFly JMX metrics (`-Dotel.jmx.target.system=wildfly`, source 4) are off: they duplicate source 3. `-Dwildfly.statistics-enabled=true` in `JAVA_OPTS` turns on WildFly's statistics in every subsystem (undertow, transactions, datasources, EJB, ...), so the `wildfly_*` metrics carry real values.

Every source, how to set it up on your own server step by step, and what you lose without WildFly statistics: **[../docs/wildfly-metrics.md](../docs/wildfly-metrics.md)**.

## 6. Cases, with real output

Recorded from this lab. Short forms: `name=` is `appserver.deployment.name`, `module=` is `.module`, `ctx=` is `.context_root`, `svc=` is `.service.name`, `ns=` is `.service.namespace`. The first column is the `service.name` Jaeger shows.

### Summary

| # | Server | Case | Lab example | Attributes set | Jaeger service / namespace |
|---|---|---|---|---|---|
| 1 | WildFly | WAR request, name declared | `POST /orders/api/orders` | name, ctx, svc, ns (4) | `order-service` / `commerce` |
| 2 | WildFly | child spans in that request (JAX-RS, JDBC, HTTP client) | `INSERT test.ORDERS` | same 4; `ctx` inherited | `order-service` / `commerce` |
| 3 | WildFly | WAR request, nothing declared | `GET /inventory/api/stock/apple` | name, ctx (2) | `inventory` / `wildfly-27-lab` |
| 4 | WildFly | EAR web module, name only declared | `GET /shop/api/checkout/apple` | name, module, ctx, svc (4) | `shop-frontend` / `wildfly-27-lab` |
| 5 | WildFly | EAR EJB module called from it | `CheckoutService.checkout` | name, module, ctx, svc (4); `ctx`, `svc` inherited | `shop-frontend` / `wildfly-27-lab` |
| 6 | WildFly | EJB timer, declared | `ReportJob.run` | name, svc, ns (3) | `reporting-job` / `back-office` |
| 7 | WildFly | deploy-time work, app declared | `CREATE TABLE` at `orders.war` startup | name, svc, ns (3) | `order-service` / `commerce` |
| 8 | Tomcat 10.1 | servlet request, declared (generic core) | `GET /catalog/items/apple` | name, ctx, svc, ns (4) | `catalog-service` / `commerce` |
| 9 | Tomcat 10.1 | request answered by a filter alone | `GET /catalog/status` | name, ctx, svc, ns (4) | `catalog-service` / `commerce` |
| 10 | Tomcat 10.1 | background scheduler, after the first request (learned) | `GET .../pricing/price?item=refresh` (root span) | name, svc, ns (3) | `catalog-service` / `commerce` |
| 11 | Tomcat 10.1 | servlet request, nothing declared | `GET /pricing/price` | name, ctx (2) | `pricing` / `tomcat-10-lab` |
| 12 | Tomcat 9 | javax servlet and javax filter | `GET /legacy/hello`, `/legacy/ping` | name, ctx (2) | `legacy` / `tomcat-9-lab` |
| 13 | all | one trace across applications | shop → orders → inventory; catalog → pricing | each span its own application's values | several services in one trace |
| 14 | all | log records | `Item banana costs 273` | same as the span at that moment | — (logs go to iyzitrace) |
| 15 | all | no application reached | `GET /no-such-app/` → 404 | none (0) | the server's own name |
| 16 | all | extension disabled | any | none (0) | the server's own name |

### Cases 1 and 2: `orders.war` (WildFly)

```
order-service  server    POST /orders/api/orders   name=orders.war  ctx=/orders  svc=order-service  ns=commerce
order-service  internal  OrderResource.create      name=orders.war  ctx=/orders  svc=order-service  ns=commerce
order-service  client    GET                       name=orders.war  ctx=/orders  svc=order-service  ns=commerce
order-service  client    INSERT test.ORDERS        name=orders.war  ctx=/orders  svc=order-service  ns=commerce
```

### Case 3: `inventory.war`, nothing declared (WildFly)

```
inventory  server    GET /inventory/api/stock/{sku}  name=inventory.war  ctx=/inventory
inventory  internal  StockResource.stock             name=inventory.war  ctx=/inventory
```

### Cases 4 and 5: `shop.ear` (WildFly)

```
shop-frontend  server    GET /shop/api/checkout/{sku}  name=shop.ear  module=shop-web.war  ctx=/shop  svc=shop-frontend
shop-frontend  internal  CheckoutResource.checkout     name=shop.ear  module=shop-web.war  ctx=/shop  svc=shop-frontend
shop-frontend  internal  CheckoutService.checkout      name=shop.ear  module=shop-ejb.jar  ctx=/shop  svc=shop-frontend
shop-frontend  internal  PricingService.priceInCents   name=shop.ear  module=shop-ejb.jar  ctx=/shop  svc=shop-frontend
shop-frontend  client    POST                          name=shop.ear  module=shop-ejb.jar  ctx=/shop  svc=shop-frontend
```

WildFly switches the class loader on the EJB call, so `module` changes to `shop-ejb.jar`. That module declares nothing, so `svc` is inherited from the web module.

### Case 6: `reports.jar` timer (WildFly)

```
reporting-job  internal  ReportJob.run             code.namespace=com.iyzitrace.lab.reports.ReportJob  code.function=run
                                                   name=reports.jar  svc=reporting-job  ns=back-office
reporting-job  client    CREATE TABLE test.ORDERS  name=reports.jar  svc=reporting-job  ns=back-office
reporting-job  client    SELECT test.ORDERS        name=reports.jar  svc=reporting-job  ns=back-office
```

Before the extension, `ReportJob.run` had only the two `code.*` attributes.

### Cases 8 to 11: Tomcat 10.1 (generic core, no vendor resolver)

```
catalog-service  server  GET /catalog/items/*  url=/catalog/items/apple   name=catalog  ctx=/catalog  svc=catalog-service  ns=commerce
catalog-service  client  GET                   url=.../pricing/price?item=apple
                                               name=catalog  ctx=/catalog  svc=catalog-service  ns=commerce
pricing          server  GET /pricing/price    name=pricing  ctx=/pricing
catalog-service  server  GET /catalog/status   name=catalog  ctx=/catalog  svc=catalog-service  ns=commerce   (filter only)
catalog-service  client  GET                   url=.../pricing/price?item=refresh  (root span, scheduler thread)
                                               name=catalog  svc=catalog-service  ns=commerce
```

- On Tomcat the name comes from the context root (`/catalog` → `catalog`). The declaration is read through the `ServletContext`.
- The scheduler's spans have **no parent and no request**. They're attributed because the extension learned the class loader of `catalog.war` from its earlier requests. Hence no `ctx`.
- Runs of the scheduler *before* the first request to `catalog` would stay unattributed (generic-core limit).

### Case 12: Tomcat 9, `javax.servlet`

```
legacy  server  GET /legacy/hello  name=legacy  ctx=/legacy
legacy  server  GET /legacy/ping   name=legacy  ctx=/legacy   (javax filter only)
```

### Case 13: one trace across applications

```
shop-frontend  server  GET /shop/api/checkout/{sku}    name=shop.ear  module=shop-web.war  ctx=/shop  svc=shop-frontend
shop-frontend  client  POST                            name=shop.ear  module=shop-ejb.jar  ctx=/shop  svc=shop-frontend
order-service  server  POST /orders/api/orders         name=orders.war  ctx=/orders  svc=order-service  ns=commerce
order-service  client  GET                             name=orders.war  ctx=/orders  svc=order-service  ns=commerce
inventory      server  GET /inventory/api/stock/{sku}  name=inventory.war  ctx=/inventory
```

The parent of each server span is remote (it came in through the HTTP `traceparent` header), so nothing is inherited across applications. Each server span is tagged by its own servlet hook.

### Case 14: log records

```
Body: Str(Item banana costs 273)
     -> appserver.deployment.context_root: Str(/catalog)
     -> appserver.deployment.name: Str(catalog)
     -> appserver.deployment.service.name: Str(catalog-service)
     -> appserver.deployment.service.namespace: Str(commerce)

Body: Str(Price refresh: 827)                       <- scheduler thread, no request
     -> appserver.deployment.name: Str(catalog)
     -> appserver.deployment.service.name: Str(catalog-service)
     -> appserver.deployment.service.namespace: Str(commerce)
```

### The same span, before and after the collector

```
agent output   resource: service.name=wildfly-27-lab
               span:     CheckoutService.checkout  name=shop.ear  module=shop-ejb.jar  ctx=/shop  svc=shop-frontend  code.*

after the      resource: service.name=shop-frontend  service.namespace=wildfly-27-lab  name=shop.ear  ctx=/shop  svc=shop-frontend
collector      span:     CheckoutService.checkout  module=shop-ejb.jar  code.*
```

"After the collector" is what Jaeger and iyzitrace receive. `groupbyattrs` has moved four of the attributes to the resource; the Jaeger UI shows them under **Process**. "Agent output" is what a backend receives when the agent exports to it directly.

## 7. Verifying

[scripts/verify.sh](scripts/verify.sh) reads Jaeger's API and prints one row per service, followed by any span that wasn't attributed. Actual output:

```
SERVICE          NAMESPACE       DEPLOYMENT     SPANS  KINDS (server/client/internal)         MODULES                    CONTEXT ROOTS (spans without)
catalog-service  commerce        catalog           84  client=32 internal=3 server=49         -                          /catalog (6)
inventory        wildfly-27-lab  inventory.war    271  internal=119 server=152                -                          /inventory (0)
legacy           tomcat-9-lab    legacy            46  server=46                              -                          /legacy (0)
order-service    commerce        orders.war       448  client=190 internal=129 server=129     -                          /orders (1)
pricing          tomcat-10-lab   pricing           35  internal=3 server=32                   -                          /pricing (0)
reporting-job    back-office     reports.jar       12  client=8 internal=4                    -                          - (12)
shop-frontend    wildfly-27-lab  shop.ear         210  client=42 internal=126 server=42       shop-ejb.jar,shop-web.war  /shop (0)

Spans without a deployment (still service.name=tomcat-10-lab):
  3x internal Response.sendError  ->
  3x server GET /no-such-app/ -> 404
Spans without a deployment (still service.name=wildfly-27-lab):
  3x server GET /no-such-app/ -> 404
```

- `catalog-service (6)`: scheduler spans, outside requests (case 10).
- `order-service (1)`: the deploy-time `CREATE TABLE` (case 7).
- `reporting-job (12)`: a timer has no context root (case 6).
- Unattributed: only the 404s for an application that doesn't exist (case 15), plus Tomcat's own `sendError` span inside that 404.

Options: `LOOKBACK=30m ./scripts/verify.sh`, plus `JAEGER`, `LIMIT` and `SERVER_SERVICES`.

**A/B check:** with `DEPLOYMENT_EXTENSION_ENABLED=false`, every span lands under its server's own name. That's the original problem. Pass the variable on every `docker compose` command, or put it in `.env`. Otherwise a later `docker compose up` recreates the servers with the extension switched on again.

WildFly 27's default `standalone.xml` also enables the legacy MicroProfile OpenTracing subsystem. Its startup logs name deployments in a similar way (`shop.ear!shop-web.war`), but it exports nothing (no-op sender) and is unrelated to OTel.

## 8. Layout

The lab lives in `lab/` of the [iyzitrace-otel-java-extensions](../README.md) repository:

```
appserver-deployment/      the extension: code, unit tests, README (setup and reference)
lab/
  apps/orders/             orders.war                                    (WildFly)
  apps/inventory/          inventory.war                                 (WildFly)
  apps/shop/               shop.ear = shop-ejb + shop-web + shop-ear     (WildFly)
  apps/reports/            reports.jar, EJB timer                        (WildFly)
  apps/tomcat/             catalog.war, pricing.war, jakarta.servlet     (Tomcat 10.1)
  apps/tomcat9/            legacy.war, javax.servlet                     (Tomcat 9)
  loadgen/traffic.sh       traffic generator
  scripts/verify.sh        Jaeger coverage report
  otel-collector.yaml      collector pipelines
  docker-compose.yml       the three servers, collector, Jaeger, load generator; all -Dotel.* settings
  Dockerfile               build stage, agent stage, and one target per server
pom.xml                    Maven parent: the extension and the lab apps; versions of the agent API, SDK and Jakarta EE
```
