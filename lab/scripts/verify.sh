#!/usr/bin/env bash
# Summarises what reached Jaeger: one row per service (declared name, else context root, else
# deployment, set by the collector), plus every span that could not be attributed to a deployment
# (those keep their server's own service.name). Requires curl and jq.
# Attributes are read from span and process (resource) tags, since groupbyattrs moves some of them.
set -euo pipefail

JAEGER="${JAEGER:-http://localhost:${JAEGER_UI_PORT:-16686}}"
LOOKBACK="${LOOKBACK:-10m}"
LIMIT="${LIMIT:-1500}"
# Server-wide service names: anything still under one of these is unattributed.
SERVER_SERVICES="${SERVER_SERVICES:-${OTEL_SERVICE_NAME:-wildfly-27-lab} tomcat-10-lab tomcat-9-lab}"

services=$(curl -fsS "$JAEGER/api/services" | jq -r '.data[]? // empty' | grep -v -x 'jaeger-all-in-one' | sort || true)
if [ -z "$services" ]; then
  echo "No services in Jaeger yet. Is the lab running and has the load generator sent traffic?" >&2
  exit 1
fi

is_server_service() {
  local server
  for server in $SERVER_SERVICES; do [ "$server" = "$1" ] && return 0; done
  return 1
}

spans_of() {
  curl -fsS "$JAEGER/api/traces?service=$1&limit=$LIMIT&lookback=$LOOKBACK" |
    jq --arg s "$1" '[.data[] | .processes as $p | .spans[]
      | select($p[.processID].serviceName == $s)
      | . + {tag: (reduce ((.tags // []) + ($p[.processID].tags // []))[] as $t ({}; .[$t.key] = $t.value))}]
      | unique_by(.spanID)'
}

row='%-16s %-15s %-14s %5s  %-38s %-26s %s\n'
printf "$row" SERVICE NAMESPACE DEPLOYMENT SPANS 'KINDS (server/client/internal)' MODULES 'CONTEXT ROOTS (spans without)'
for s in $services; do
  is_server_service "$s" && continue
  spans_of "$s" | jq -r --arg s "$s" '
    def values(k): [.[].tag[k] // empty] | unique | if length == 0 then "-" else join(",") end;
    (group_by(.tag["span.kind"] // "internal") | map("\(.[0].tag["span.kind"] // "internal")=\(length)") | join(" ")) as $kinds
    | ([.[] | select(.tag["appserver.deployment.context_root"] == null)] | length) as $noroot
    | [$s, values("service.namespace"), values("appserver.deployment.name"), length, $kinds,
       values("appserver.deployment.module"), "\(values("appserver.deployment.context_root")) (\($noroot))"]
    | @tsv' |
    awk -F'\t' -v row="$row" '{ printf row, $1, $2, $3, $4, $5, $6, $7 }'
done

echo
unattributed=0
for s in $services; do
  is_server_service "$s" || continue
  unattributed=1
  echo "Spans without a deployment (still service.name=$s):"
  spans_of "$s" | jq -r '
    group_by([.tag["span.kind"], .operationName, (.tag["url.path"] // "")])[]
    | "  \(length)x \(.[0].tag["span.kind"] // "internal") \(.[0].operationName) \(.[0].tag["url.path"] // "") -> \(.[0].tag["http.response.status_code"] // "")"'
done
[ "$unattributed" = 0 ] && echo "Every span was attributed to a deployment."
exit 0
