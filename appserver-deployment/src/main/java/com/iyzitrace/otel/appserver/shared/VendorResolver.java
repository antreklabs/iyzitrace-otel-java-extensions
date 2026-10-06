package com.iyzitrace.otel.appserver.shared;

/**
 * Server-specific knowledge: maps a deployment's class loader to the deployment that owns it. This
 * covers what the generic servlet hook never sees: EAR modules, EJB jars, timers and MDBs, and work
 * done at deploy time.
 *
 * <p>Implementations must be fast (called for spans without an attributed parent), must cache, and
 * must return {@code null} for class loaders they don't recognise.
 */
public interface VendorResolver {

  DeploymentInfo resolve(ClassLoader classLoader);
}
