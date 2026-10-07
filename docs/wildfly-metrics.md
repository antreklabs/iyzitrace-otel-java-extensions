# WildFly metrics with OpenTelemetry

How to collect every useful metric from a WildFly server with the OpenTelemetry Java agent and an OpenTelemetry Collector: JVM, HTTP, JMX, and WildFly's own subsystem statistics. Each source is set up step by step, with what it gives you and what it costs.

This is independent of the [appserver-deployment extension](../appserver-deployment/README.md). The extension tags spans and log records, not metrics.

Tested on WildFly 27.0.1, agent 2.21.0, collector-contrib 0.159.0, in the [lab](../lab/README.md).

## Contents

1. [The metric sources](#1-the-metric-sources): [overlaps](#overlaps), [source 3 or source 4?](#source-3-or-source-4), [metric mapping](#metric-mapping)
2. [Step 1: send the agent's metrics to the collector](#step-1-send-the-agents-metrics-to-the-collector)
3. [Step 2: JVM metrics](#step-2-jvm-metrics)
4. [Step 3: HTTP metrics](#step-3-http-metrics)
5. [Step 4: turn on WildFly statistics](#step-4-turn-on-wildfly-statistics)
6. [Step 5: expose WildFly's `/metrics` endpoint](#step-5-expose-wildflys-metrics-endpoint)
7. [Step 6: scrape `/metrics` with the collector](#step-6-scrape-metrics-with-the-collector)
8. [Step 7 (alternative to steps 5–6): the agent's WildFly JMX metrics](#step-7-alternative-to-steps-56-the-agents-wildfly-jmx-metrics)
9. [Step 8: verify](#step-8-verify)
10. [Recommended setups](#recommended-setups)
11. [Clusters](#clusters)
12. [Limits](#limits)

## 1. The metric sources

| # | Source | Produced by | Reaches the collector by | Metrics | Needs WildFly statistics |
|---|---|---|---|---|---|
| 1 | JVM runtime | agent | OTLP push | `jvm.*` | no |
| 2 | HTTP instrumentation | agent | OTLP push | `http.server.request.duration`, `http.client.request.duration` | no |
| 3 | WildFly `/metrics` endpoint | WildFly's `metrics` subsystem | collector scrape (Prometheus format) | `wildfly_*`, about 130 names | **yes**, for most of them |
| 4 | WildFly JMX rules (optional) | agent | OTLP push | `wildfly.*`, 15 names | **yes** |

> **Sources 3 and 4 overlap completely: use one, not both.** Source 4 is a subset of source 3, read through a different path. Use source 3; use source 4 **only** when the collector can't reach WildFly's management port. Running both sends every source 4 metric twice, under two names. See [Source 3 or source 4?](#source-3-or-source-4).

The agent also sends a few metrics about itself (`otlp.exporter.exported`, `otlp.exporter.seen`, `processedSpans`, `processedLogs`, `queueSize`). They describe the agent's export health, not WildFly.

```
 WildFly JVM                                            OpenTelemetry Collector
┌──────────────────────────────────────┐               ┌───────────────────────────┐
│ OTel Java agent                      │  OTLP push    │ receivers:                │
│   source 1: jvm.*                    │──────────────▶│   otlp                    │
│   source 2: http.*                   │               │                           │
│   source 4: wildfly.* (JMX, optional)│               │                           │──▶ backend
│                                      │  scrape :9990 │                           │
│ metrics subsystem: /metrics          │◀──────────────│   prometheus/wildfly      │
│   source 3: wildfly_*                │               │                           │
└──────────────────────────────────────┘               └───────────────────────────┘
```

### Overlaps

| Sources | Overlap | What to do |
|---|---|---|
| **3 and 4** | **full**: the same WildFly statistics, read two ways. Every source 4 metric has a source 3 equivalent ([mapping](#metric-mapping)) | **pick one**: source 3, or source 4 if port 9990 is unreachable |
| 2 and 3 | partial: both count the same HTTP requests, at different layers (agent histogram per route vs Undertow counters per deployment and servlet) | keep both; they complement each other. Don't add their counts together |
| 1 and the `/metrics` endpoint's `base_*` / `vendor_*` | full: both are JVM memory, GC and threads | already handled: step 6 drops `base_*` and `vendor_*` |

Without overlaps there are **three sources**: JVM (1), HTTP (2), and WildFly statistics through either 3 or 4.

### Source 3 or source 4?

Both read WildFly's runtime statistics from its management model, so both need [step 4](#step-4-turn-on-wildfly-statistics) and both show the same values. They differ in who reads them, how they travel, and how much they cover.

```
 source 3:  WildFly management model ──▶ metrics subsystem ──▶ GET :9990/metrics ◀── collector scrapes (pull)
 source 4:  WildFly management model ──▶ JMX MBeans ──▶ agent's JMX rules ──▶ OTLP ──▶ collector (push)
```

| | Source 3: `/metrics` endpoint | Source 4: agent JMX rules |
|---|---|---|
| Read by | WildFly's own `metrics` subsystem | the agent, in-process, from `jboss.as:*` MBeans |
| Transport | **pull**: the collector scrapes over HTTP | **push**: OTLP, together with the agent's other metrics |
| Network | collector → WildFly port 9990 must be open | none beyond agent → collector, which you already have |
| Exposed endpoint | `/metrics` on the management interface, no login by default ([5.3](#53-access-is-restricted)) | nothing exposed |
| Coverage | **about 130 names**, every subsystem: datasources (60), undertow (17), jca, ejb3, transactions, batch, ee, io | **15 names**, 4 areas: Undertow listeners, sessions, transactions, datasource pool |
| Detail | per deployment, sub-deployment and servlet; per EJB; full pool statistics (wait / get / usage times, timeouts, XA) | per listener and per deployment; pool used / idle / wait count only |
| Not in the other | EJB, JCA, EE executors, IO, batch, min/max request times, transaction timeouts and heuristics, most pool metrics | `wildfly.session.active.limit` (also in 3 as `wildfly_undertow_max_active_sessions`); nothing else |
| Names | Prometheus style: `wildfly_undertow_request_count_total`, labels `deployment`, `servlet`, `data_source`, ... | OTel style: `wildfly.request.count`, attributes `wildfly.deployment`, `wildfly.listener`, `db.client.connection.pool.name`, ... |
| Units and types | Prometheus counters and gauges; times in seconds | OTel counters / up-down counters with units (`{request}`, `s`, `By`) |
| Resource attributes | from the scrape: `service.name` = job name, `service.instance.id` = target (`wildfly-1:9990`) | the agent's resource, the same as on its JVM metrics and traces (`service.name`, `service.instance.id`, `host.*`, `process.*`) |
| Interval | `scrape_interval` (step 6) | `otel.metric.export.interval` (step 1) |
| Setup | statistics + `metrics` subsystem + management binding + collector receiver | statistics + one JVM option |
| Extending | automatic: every subsystem with statistics is included | write your own rule file (`-Dotel.jmx.config=<file>.yaml`) for any MBean |

**Rule of thumb:** source 3 gives far more detail for one extra network path. Source 4 is the fallback when that path can't be opened, for example when the management interface must stay on `127.0.0.1` and the collector runs on another host.

### Metric mapping

Every source 4 metric and its source 3 equivalent (from the agent's `wildfly.yaml` rules, agent 2.21.0):

| Source 4 (agent JMX) | Source 3 (`/metrics`) | What |
|---|---|---|
| `wildfly.request.count` | `wildfly_undertow_request_count_total` | requests per listener |
| `wildfly.request.duration.sum` | `wildfly_undertow_processing_time_total_seconds` | total processing time per listener |
| `wildfly.error.count` | `wildfly_undertow_error_count_total` | 5xx responses per listener |
| `wildfly.network.io` (`network.io.direction`) | `wildfly_undertow_bytes_sent_total_bytes`, `wildfly_undertow_bytes_received_total_bytes` | bytes per listener |
| `wildfly.session.created` | `wildfly_undertow_sessions_created_total` | per deployment |
| `wildfly.session.active.count` | `wildfly_undertow_active_sessions` | per deployment |
| `wildfly.session.active.limit` | `wildfly_undertow_max_active_sessions` | per deployment |
| `wildfly.session.expired` | `wildfly_undertow_expired_sessions_total` | per deployment |
| `wildfly.session.rejected` | `wildfly_undertow_rejected_sessions_total` | per deployment |
| `wildfly.db.client.connection.count` (`state=used`) | `wildfly_datasources_pool_active_count` | per datasource |
| `wildfly.db.client.connection.count` (`state=idle`) | `wildfly_datasources_pool_idle_count` | per datasource |
| `wildfly.db.client.connection.wait.count` | `wildfly_datasources_pool_wait_count` | per datasource |
| `wildfly.transaction.count` | `wildfly_transactions_number_of_inflight_transactions` | in-flight transactions |
| `wildfly.transaction.created` | `wildfly_transactions_number_of_transactions_total` | |
| `wildfly.transaction.committed` | `wildfly_transactions_number_of_committed_transactions_total` | |
| `wildfly.transaction.rollback` (`wildfly.rollback.cause`) | `wildfly_transactions_number_of_application_rollbacks_total`, `..._resource_rollbacks_total`, `..._system_rollbacks_total` | |

## Step 1: send the agent's metrics to the collector

Sources 1, 2 and (if you use it) 4 come from the agent. Add to `bin/standalone.conf`, next to the existing `-javaagent` option:

```sh
JAVA_OPTS="$JAVA_OPTS -Dotel.service.name=wildfly-prod"
JAVA_OPTS="$JAVA_OPTS -Dotel.exporter.otlp.endpoint=http://otel-collector:4318"
JAVA_OPTS="$JAVA_OPTS -Dotel.exporter.otlp.protocol=http/protobuf"
JAVA_OPTS="$JAVA_OPTS -Dotel.metrics.exporter=otlp"
JAVA_OPTS="$JAVA_OPTS -Dotel.metric.export.interval=15000"
```

- `otel.metrics.exporter=otlp` is the agent's default; setting it makes the intent explicit.
- `otel.metric.export.interval` is in milliseconds. The default is 60000; 15000 matches the scrape interval in step 6, so all sources have the same resolution.
- `otel.service.name` is reused as the scrape job name in step 6, so all sources land under the same service.

## Step 2: JVM metrics

**On by default.** Nothing to set.

| Group | Metrics |
|---|---|
| Memory | `jvm.memory.used`, `jvm.memory.committed`, `jvm.memory.limit`, `jvm.memory.init`, `jvm.memory.used_after_last_gc` (per pool: heap, metaspace, ...) |
| GC | `jvm.gc.duration` (per collector and action) |
| Threads | `jvm.thread.count` (per state, daemon or not) |
| Classes | `jvm.class.count`, `jvm.class.loaded`, `jvm.class.unloaded` |
| CPU | `jvm.cpu.time`, `jvm.cpu.count`, `jvm.cpu.recent_utilization` |

**Optional:** more JVM metrics, marked experimental by OpenTelemetry (names may change between agent versions):

```sh
JAVA_OPTS="$JAVA_OPTS -Dotel.instrumentation.runtime-telemetry.emit-experimental-telemetry=true"
```

With it, the lab also receives `jvm.buffer.*` (direct and mapped buffers), `jvm.file_descriptor.count`, `jvm.system.cpu.load_1m` and `jvm.system.cpu.utilization`.

WildFly's `/metrics` endpoint has its own JVM metrics (`base_*`, `vendor_*`). Step 6 drops them, because they duplicate these.

## Step 3: HTTP metrics

**On by default.** Nothing to set.

| Metric | What | Main attributes |
|---|---|---|
| `http.server.request.duration` | every request WildFly serves (histogram: count, rate and latency) | `http.request.method`, `http.response.status_code`, `http.route`, `error.type` |
| `http.client.request.duration` | every outgoing HTTP call the applications make | `http.request.method`, `http.response.status_code`, `server.address`, `error.type` |

These give request rate, error rate and latency without any WildFly configuration. `http.route` starts with the context root (`/orders/...`), so it's the way to split them per application.

## Step 4: turn on WildFly statistics

WildFly keeps most runtime statistics off. Their metrics are still exported in steps 5–7, but with dead values. **Nothing errors, so the gap is easy to miss.**

### What you lose without statistics

Measured in the lab by switching statistics off under steady traffic:

| Area | Without statistics | Covered elsewhere? |
|---|---|---|
| **Datasource pool**: active, idle, available, max used, wait / blocking / get / usage times, timeouts, created / destroyed, XA commit times (28 metrics) | 0 | **no**: the only view of pool exhaustion and slow connection gets |
| **Undertow**: request count, error count, bytes sent / received, min / max / total request time | 0 | mostly, by `http.server.request.duration` (step 3) |
| **Transactions**: total, committed, aborted, timed out, heuristics, application / resource rollbacks | **frozen** at their last value, which looks healthy while recording nothing | no |
| **EJB**: invocations, execution time, wait time, peak concurrency, per bean | 0 | no |
| **Agent JMX metrics** (source 4, step 7) | 0 | – |

Unaffected: IO worker threads and connections, EJB and EE thread pools, active requests, all agent metrics (steps 2 and 3).

### Option A: one property for everything (recommended)

In the default profiles, every subsystem's `statistics-enabled` is an expression with a per-subsystem property that falls back to one global property:

```xml
<subsystem xmlns="urn:jboss:domain:undertow:13.0" ... statistics-enabled="${wildfly.undertow.statistics-enabled:${wildfly.statistics-enabled:false}}">
<datasource ... statistics-enabled="${wildfly.datasources.statistics-enabled:${wildfly.statistics-enabled:false}}">
<coordinator-environment statistics-enabled="${wildfly.transactions.statistics-enabled:${wildfly.statistics-enabled:false}}"/>
<statistics enabled="${wildfly.ejb3.statistics-enabled:${wildfly.statistics-enabled:false}}"/>
```

So one option turns them all on at the next start, without editing `standalone.xml`:

```sh
JAVA_OPTS="$JAVA_OPTS -Dwildfly.statistics-enabled=true"
```

This is what the [lab](../lab/docker-compose.yml) uses. Check your profile first: `grep -n 'statistics' standalone.xml` should show these expressions. A profile edited earlier may have plain `true` / `false` values instead, which the property doesn't change; use option B there.

To enable only some subsystems, use the per-subsystem properties instead, e.g. `-Dwildfly.datasources.statistics-enabled=true`.

### Option B: per subsystem with the CLI

Use this when the profile has plain `statistics-enabled="false"` values instead of the expressions above. The lab doesn't need it: its stock profile has the expressions, so option A is enough. Connect with `bin/jboss-cli.sh -c`:

```
/subsystem=undertow:write-attribute(name=statistics-enabled,value=true)
/subsystem=transactions:write-attribute(name=statistics-enabled,value=true)
/subsystem=ejb3:write-attribute(name=statistics-enabled,value=true)
/subsystem=datasources/data-source=<your-ds>:write-attribute(name=statistics-enabled,value=true)
:reload
```

- The change is saved in `standalone.xml` and survives restarts.
- Undertow and transactions take effect immediately. The datasource change needs `:reload`.
- Repeat the datasource line for every datasource (`/subsystem=datasources:read-children-names(child-type=data-source)` lists them).

**Minimal choice:** if you want as little change as possible, enable only the datasource statistics (`-Dwildfly.datasources.statistics-enabled=true`, or the datasource CLI line). Pool metrics are the one thing no other source covers.

**Cost:** statistics are counters and timers inside WildFly. The overhead is usually small; datasource statistics add work to every connection checkout. Not benchmarked here: if latency is critical, compare before and after on a test environment.

## Step 5: expose WildFly's `/metrics` endpoint

WildFly serves the endpoint itself; nothing is installed. It's on the **management interface**: `http://<host>:9990/metrics`, in Prometheus text format.

### 5.1 The `metrics` subsystem is in the profile

It is in the default `standalone.xml`, `standalone-ha.xml` and `standalone-full-ha.xml` of WildFly 27 (and JBoss EAP 7.3+):

```xml
<subsystem xmlns="urn:wildfly:metrics:1.0" security-enabled="false" exposed-subsystems="*" prefix="${wildfly.metrics.prefix:wildfly}"/>
```

| Attribute | Meaning |
|---|---|
| `exposed-subsystems="*"` | export the statistics of every subsystem |
| `security-enabled="false"` | no login needed |
| `prefix` | metric name prefix: `wildfly_...` |

If it's missing:

```
/extension=org.wildfly.extension.metrics:add
/subsystem=metrics:add(exposed-subsystems=[*],security-enabled=false)
:reload
```

### 5.2 The collector can reach the management port

The management interface listens on `127.0.0.1` by default. Either:

- run the collector on the same host as WildFly (scrape `localhost:9990`), or
- bind the management interface to an internal address:

  ```sh
  ./standalone.sh -bmanagement 10.0.0.12
  ```

  or permanently in `standalone.xml`: `<interface name="management"><inet-address value="${jboss.bind.address.management:10.0.0.12}"/></interface>`.

With a port offset (`-Djboss.socket.binding.port-offset=100`), the port moves too (`10090`).

### 5.3 Access is restricted

With `security-enabled="false"`, anyone who reaches port 9990 can read the metrics. Keep the port on an internal network and open it only to the collector (firewall, security group).

`security-enabled="true"` puts the endpoint behind the management interface's authentication. In the default configuration that is **DIGEST** only (`management-http-authentication`), which the collector's Prometheus receiver doesn't support. Network isolation is the simpler option.

### 5.4 Check

From the collector's host:

```sh
curl -s http://<wildfly-host>:9990/metrics | grep -c '^wildfly_'      # > 0
curl -s http://<wildfly-host>:9990/metrics | grep '^wildfly_undertow_request_count_total'
```

The request count must grow with traffic. If it stays 0, statistics are off (step 4).

## Step 6: scrape `/metrics` with the collector

Add a `prometheus` receiver to the collector configuration and put it in the metrics pipeline. Needs the **contrib** distribution of the collector (`otel/opentelemetry-collector-contrib`).

```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318

  prometheus/wildfly:
    config:
      scrape_configs:
        - job_name: wildfly-prod          # becomes service.name: use the agent's otel.service.name
          scrape_interval: 15s            # same as otel.metric.export.interval
          metrics_path: /metrics
          static_configs:
            - targets: ["wildfly-1:9990"]
          metric_relabel_configs:
            # base_* and vendor_* are JVM metrics; the agent already sends them as jvm.* (step 2).
            - source_labels: [__name__]
              regex: "(base|vendor)_.*"
              action: drop

service:
  pipelines:
    metrics:
      receivers: [otlp, prometheus/wildfly]
      processors: [batch]
      exporters: [<your exporter>]
```

What the receiver sets on every scraped metric:

| Resource attribute | Value |
|---|---|
| `service.name` | the `job_name` |
| `service.instance.id` | the target, e.g. `wildfly-1:9990` |

### What arrives

| Subsystem | Names | Examples | Needs statistics |
|---|---|---|---|
| datasources | 60 | pool active / idle / in use, wait time, timeouts, prepared statement cache | yes |
| undertow | 17 | request count and time per listener, per deployment and per servlet; sessions; bytes | yes |
| jca | 13 | work manager threads, queue size, rejected work | not measured (idle in the lab) |
| ejb3 | 11 | invocations, execution / wait time, peak concurrency per bean; EJB thread pool | yes, except the thread pool |
| transactions | 11 | total, committed, aborted, timed out, heuristics, rollbacks | yes |
| batch | 7 | batch job thread pool | not measured (idle in the lab) |
| ee | 7 | managed executor threads, queue size, hung threads | not measured (idle in the lab) |
| io | 6 | IO worker threads, busy task threads, connections | no |
| request | 1 | active requests | no |

Names stay in Prometheus style: `wildfly_undertow_request_count_total`, not `wildfly.request.count`. Labels identify the resource: `deployment`, `subdeployment`, `servlet`, `data_source`, `stateless_session_bean`, `http_listener`, ...

## Step 7 (alternative to steps 5–6): the agent's WildFly JMX metrics

> **Skip this step if steps 5–6 work.** It's source 4, which duplicates source 3 ([Source 3 or source 4?](#source-3-or-source-4)). Use it **instead of** steps 5–6 when the collector can't reach port 9990, e.g. the management interface must stay on `127.0.0.1` and the collector runs on another host.

The agent reads WildFly's statistics over JMX, in-process, and pushes them with OTLP:

```sh
JAVA_OPTS="$JAVA_OPTS -Dotel.jmx.target.system=wildfly"
```

You get the 15 `wildfly.*` metrics listed in the [metric mapping](#metric-mapping): Undertow listeners, sessions per deployment, transactions, and the datasource pool (used / idle / wait count). Step 4 still applies: without statistics they all read 0.

If you used this step and later open port 9990 for steps 5–6, remove `-Dotel.jmx.target.system=wildfly`; otherwise every metric arrives twice.

## Step 8: verify

1. **Endpoint:** the `curl` checks from [5.4](#54-check).
2. **Collector:** temporarily add a `debug` exporter with `verbosity: detailed` to the metrics pipeline and list the metric names that arrive:

   ```sh
   docker logs <collector> 2>&1 | grep -E '\-> Name: ' | sed 's/.*Name: //' | sort -u
   ```

   Expect `jvm.*`, `http.server.request.duration`, and either `wildfly_*` (steps 5–6) or `wildfly.*` (step 7). Both at once means sources 3 and 4 are both on.
3. **Values:** under traffic, `wildfly_undertow_request_count_total` and `wildfly_transactions_number_of_transactions_total` grow, and `wildfly_datasources_pool_*` are not all 0.
4. **Backend:** `jvm.*` and `wildfly_*` appear under the same `service.name`.

Switch the `debug` exporter back to `verbosity: basic` afterwards; `detailed` is very verbose.

## Recommended setups

**Full (recommended):** steps 1–6. Sources 1, 2 and 3; no `-Dotel.jmx.target.system`.

```sh
# bin/standalone.conf
JAVA_OPTS="$JAVA_OPTS -javaagent:/opt/opentelemetry-javaagent.jar"
JAVA_OPTS="$JAVA_OPTS -Dotel.service.name=wildfly-prod"
JAVA_OPTS="$JAVA_OPTS -Dotel.exporter.otlp.endpoint=http://otel-collector:4318"
JAVA_OPTS="$JAVA_OPTS -Dotel.exporter.otlp.protocol=http/protobuf"
JAVA_OPTS="$JAVA_OPTS -Dotel.metric.export.interval=15000"
JAVA_OPTS="$JAVA_OPTS -Dotel.instrumentation.runtime-telemetry.emit-experimental-telemetry=true"
JAVA_OPTS="$JAVA_OPTS -Dwildfly.statistics-enabled=true"
```

plus `-bmanagement <internal-ip>` (or a collector on the same host) and the scrape config from step 6.

**Minimal:** steps 1–3 only. JVM and HTTP metrics, no WildFly change at all. Add datasource statistics and steps 5–6 when you need pool visibility.

**No access to port 9990:** steps 1–4 and 7. Sources 1, 2 and 4; less WildFly detail ([what's missing](#source-3-or-source-4)).

## Clusters

Several standalone WildFly instances running the same applications:

- Give every node the **same** `otel.service.name`, and tell the nodes apart by instance:

  ```sh
  JAVA_OPTS="$JAVA_OPTS -Dotel.resource.attributes=service.instance.id=${HOSTNAME},host.name=${HOSTNAME}"
  ```

  Otherwise the agent metrics of different nodes can fall into the same series.
- List **one scrape target per node** in step 6. Each target becomes its own `service.instance.id` (`wildfly-1:9990`, `wildfly-2:9990`).
- Enable statistics on **every** node (step 4); it's a per-node setting.
- Counts are per node; sum over `service.instance.id` for cluster totals. Replicated sessions are counted on each node that holds them.
- Neither source has cluster-specific metrics (Infinispan caches, JGroups membership).

## Limits

| Limit | Workaround |
|---|---|
| No metric carries `appserver.deployment.*` or a per-application `service.name`; everything is under the server's `service.name` | group `http.server.request.duration` by `http.route`; use the `deployment` label on `wildfly_*` metrics; or derive per-application request metrics from spans with the collector's `spanmetrics` connector |
| Statistics are off by default; metrics then read 0 or freeze | step 4 |
| `/metrics` has no login by default, and its secured mode needs DIGEST | network isolation (5.3) |
| `wildfly_*` names are Prometheus-style, `jvm.*` / `wildfly.*` are OTel-style | expected; dashboards use both styles |
| Newer WildFly versions add a Micrometer subsystem that pushes OTLP | not covered here; check that the `metrics` subsystem is still in your version's profile before relying on step 5 |
