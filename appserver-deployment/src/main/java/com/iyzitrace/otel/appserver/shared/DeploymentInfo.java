package com.iyzitrace.otel.appserver.shared;

import java.util.Objects;

/** Identity of a deployment. Only {@code name} is required; everything else may be {@code null}. */
public final class DeploymentInfo {

  private final String name;
  private final String module;
  private final String contextRoot;
  private final String serviceName;
  private final String serviceNamespace;

  public DeploymentInfo(String name, String module) {
    this(name, module, null, null, null);
  }

  public DeploymentInfo(
      String name, String module, String contextRoot, String serviceName, String serviceNamespace) {
    this.name = Objects.requireNonNull(name, "name");
    this.module = module;
    this.contextRoot = contextRoot;
    this.serviceName = serviceName;
    this.serviceNamespace = serviceNamespace;
  }

  public String name() {
    return name;
  }

  /** Module inside an EAR; {@code null} for top-level deployments. */
  public String module() {
    return module;
  }

  /** Only set for web applications seen through a servlet request. */
  public String contextRoot() {
    return contextRoot;
  }

  public String serviceName() {
    return serviceName;
  }

  public String serviceNamespace() {
    return serviceNamespace;
  }

  public boolean declaresService() {
    return serviceName != null || serviceNamespace != null;
  }

  public DeploymentInfo withService(String serviceName, String serviceNamespace) {
    return new DeploymentInfo(name, module, contextRoot, serviceName, serviceNamespace);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DeploymentInfo)) {
      return false;
    }
    DeploymentInfo that = (DeploymentInfo) o;
    return name.equals(that.name)
        && Objects.equals(module, that.module)
        && Objects.equals(contextRoot, that.contextRoot)
        && Objects.equals(serviceName, that.serviceName)
        && Objects.equals(serviceNamespace, that.serviceNamespace);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, module, contextRoot, serviceName, serviceNamespace);
  }

  @Override
  public String toString() {
    return "DeploymentInfo{name=" + name + ", module=" + module + ", contextRoot=" + contextRoot
        + ", serviceName=" + serviceName + ", serviceNamespace=" + serviceNamespace + "}";
  }
}
