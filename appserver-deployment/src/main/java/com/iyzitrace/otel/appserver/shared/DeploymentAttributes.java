package com.iyzitrace.otel.appserver.shared;

import io.opentelemetry.api.common.AttributeKey;

/**
 * Attribute keys written by this extension. There is no semantic convention for application
 * server deployments; the {@code appserver.} prefix avoids the {@code app.*} namespace that
 * semantic conventions use for mobile and browser applications.
 */
public final class DeploymentAttributes {

  /** Deployment name: {@code orders.war}, {@code shop.ear} (from a vendor resolver) or {@code catalog} (from the context root). */
  public static final AttributeKey<String> DEPLOYMENT_NAME =
      AttributeKey.stringKey("appserver.deployment.name");

  /** Module inside an EAR, e.g. {@code shop-web.war}; absent for top-level deployments. */
  public static final AttributeKey<String> MODULE =
      AttributeKey.stringKey("appserver.deployment.module");

  /** Servlet context root, e.g. {@code /orders}; only known inside requests that reached the web app. */
  public static final AttributeKey<String> CONTEXT_ROOT =
      AttributeKey.stringKey("appserver.deployment.context_root");

  /**
   * {@code otel.service.name} declared by the deployment in {@code microprofile-config.properties}.
   * A span attribute, not the resource {@code service.name}: the agent has one resource per JVM, so
   * only a collector or backend can promote it.
   */
  public static final AttributeKey<String> DECLARED_SERVICE_NAME =
      AttributeKey.stringKey("appserver.deployment.service.name");

  /** {@code service.namespace} declared by the deployment in {@code otel.resource.attributes}. */
  public static final AttributeKey<String> DECLARED_SERVICE_NAMESPACE =
      AttributeKey.stringKey("appserver.deployment.service.namespace");

  private DeploymentAttributes() {}
}
