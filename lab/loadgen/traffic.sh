#!/bin/sh
# Steady, mixed traffic across all servers and deployments. Every 7th round also hits the failure paths.
set -u

WILDFLY="${WILDFLY:-http://wildfly:8080}"
TOMCAT10="${TOMCAT10:-http://tomcat10:8080}"
TOMCAT9="${TOMCAT9:-http://tomcat9:8080}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-2}"
SKUS="apple banana cherry dates elderberry fig grape"

hit() {
  method="$1"; url="$2"
  code=$(curl -s -o /dev/null -w '%{http_code}' -X "$method" "$url")
  echo "$(date +%H:%M:%S) $code $method $url"
}

round=0
while true; do
  round=$((round + 1))

  # WildFly
  for sku in $SKUS; do
    case $(( (round + ${#sku}) % 4 )) in
      0) hit GET  "$WILDFLY/shop/api/checkout/$sku?qty=2" ;;      # shop.ear -> orders.war -> inventory.war
      1) hit POST "$WILDFLY/orders/api/orders?sku=$sku&qty=1" ;;  # orders.war -> inventory.war
      2) hit GET  "$WILDFLY/inventory/api/stock/$sku" ;;          # inventory.war only
      3) hit GET  "$WILDFLY/orders/api/orders" ;;                 # orders.war + JDBC
    esac
  done
  hit GET "$WILDFLY/inventory/health"
  # Session + managed executor + batch job, so those wildfly_* metrics move. Not every round: each call
  # adds a batch execution to WildFly's in-memory job repository.
  if [ $((round % 5)) -eq 0 ]; then
    hit POST "$WILDFLY/inventory/api/restock/$(echo $SKUS | cut -d' ' -f$(( round % 7 + 1 )))"
  fi

  # Tomcat 10.1 (jakarta) and Tomcat 9 (javax)
  hit GET "$TOMCAT10/catalog/items/$(echo $SKUS | cut -d' ' -f$(( round % 7 + 1 )))"  # catalog -> pricing
  hit GET "$TOMCAT10/catalog/status"                                                  # filter only
  hit GET "$TOMCAT9/legacy/hello"                                                     # javax servlet
  hit GET "$TOMCAT9/legacy/ping"                                                      # javax filter only

  if [ $((round % 7)) -eq 0 ]; then
    hit GET  "$WILDFLY/inventory/api/stock/broken"                # 500 in inventory.war
    hit POST "$WILDFLY/orders/api/orders?sku=broken"              # 502 in orders.war, 500 downstream
    hit GET  "$WILDFLY/no-such-app/"                              # 404, no deployment at all
    hit GET  "$TOMCAT10/catalog/items/broken"                     # 502 in catalog, 500 in pricing
    hit GET  "$TOMCAT10/no-such-app/"                             # 404 on Tomcat
  fi
  sleep "$INTERVAL_SECONDS"
done
