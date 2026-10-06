package com.iyzitrace.otel.appserver.shared;

/**
 * All known vendor resolvers, asked in order. To support another server's EARs and EJB jars, add an
 * implementation here (and to the helper list in the instrumentation module).
 */
public final class VendorResolvers {

  private final VendorResolver[] resolvers = {new JBossModulesResolver()};

  public DeploymentInfo resolve(ClassLoader classLoader) {
    if (classLoader == null) {
      return null;
    }
    for (VendorResolver resolver : resolvers) {
      DeploymentInfo info = resolver.resolve(classLoader);
      if (info != null) {
        return info;
      }
    }
    return null;
  }
}
