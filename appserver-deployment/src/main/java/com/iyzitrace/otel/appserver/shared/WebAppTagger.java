package com.iyzitrace.otel.appserver.shared;

import io.opentelemetry.api.trace.Span;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Called from the servlet and filter advice, for {@code jakarta.servlet} and {@code javax.servlet}
 * alike (servlet objects are passed as {@code Object}, so this class depends on neither).
 *
 * <p>The agent starts the HTTP SERVER span in the container before the request is routed to a web
 * application, so at span start nothing identifies it. When the request reaches a servlet or filter,
 * the span is current and the servlet context identifies the web application; the span is tagged
 * here.
 *
 * <p>The first request per web application registers it: context root, declared service (read
 * through the servlet context, so it works on any container) and, if a vendor resolver recognises
 * the thread context class loader, the vendor's deployment and module name.
 *
 * <p>Injected into the class loader of each instrumented servlet or filter class.
 */
public final class WebAppTagger {

  /** Passed to {@code ServletContext#getResourceAsStream}. */
  public static final String CONFIG_PATH = "/WEB-INF/classes/" + DeclaredService.CONFIG_RESOURCE;

  private static final VendorResolvers VENDORS = new VendorResolvers();

  // Keyed by ServletContext; weak so undeployed web applications can be collected.
  private static final Map<Object, DeploymentInfo> WEB_APPS =
      Collections.synchronizedMap(new WeakHashMap<>());

  public static boolean isKnown(Object servletContext) {
    return WEB_APPS.containsKey(servletContext);
  }

  /** Closes {@code config}. */
  public static void register(Object servletContext, String contextPath, InputStream config) {
    WEB_APPS.put(
        servletContext,
        webApp(
            contextPath,
            DeclaredService.read(config),
            VENDORS.resolve(Thread.currentThread().getContextClassLoader())));
  }

  public static void tag(Object servletContext) {
    DeploymentInfo info = WEB_APPS.get(servletContext);
    if (info == null) {
      return;
    }
    Span span = Span.current();
    if (!span.isRecording()) {
      return;
    }
    span.setAttribute(DeploymentAttributes.DEPLOYMENT_NAME, info.name());
    span.setAttribute(DeploymentAttributes.CONTEXT_ROOT, info.contextRoot());
    if (info.module() != null) {
      span.setAttribute(DeploymentAttributes.MODULE, info.module());
    }
    if (info.serviceName() != null) {
      span.setAttribute(DeploymentAttributes.DECLARED_SERVICE_NAME, info.serviceName());
    }
    if (info.serviceNamespace() != null) {
      span.setAttribute(DeploymentAttributes.DECLARED_SERVICE_NAMESPACE, info.serviceNamespace());
    }
  }

  /**
   * Combines what the servlet context says with what a vendor resolver knows. The vendor's names
   * win ({@code orders.war} rather than {@code orders}); without one, the name comes from the
   * context root ({@code /catalog} becomes {@code catalog}, the root context becomes {@code ROOT}).
   * A declaration found by the vendor resolver (the module's own file) wins over the WAR's file.
   */
  static DeploymentInfo webApp(String contextPath, String[] declared, DeploymentInfo vendor) {
    String contextRoot = contextPath == null || contextPath.isEmpty() ? "/" : contextPath;
    String name =
        vendor != null
            ? vendor.name()
            : "/".equals(contextRoot) ? "ROOT" : contextRoot.substring(1).replace('/', '#');
    String module = vendor != null ? vendor.module() : null;
    String serviceName = null;
    String serviceNamespace = null;
    if (vendor != null && vendor.declaresService()) {
      serviceName = vendor.serviceName();
      serviceNamespace = vendor.serviceNamespace();
    } else if (declared != null) {
      serviceName = declared[0];
      serviceNamespace = declared[1];
    }
    return new DeploymentInfo(name, module, contextRoot, serviceName, serviceNamespace);
  }

  private WebAppTagger() {}
}
