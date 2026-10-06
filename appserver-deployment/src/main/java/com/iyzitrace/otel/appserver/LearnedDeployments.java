package com.iyzitrace.otel.appserver;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import com.iyzitrace.otel.appserver.shared.DeploymentInfo;

/**
 * Generic fallback for servers without a vendor resolver (Tomcat, Jetty, ...): learns which class
 * loader belongs to which web application, so work outside requests (scheduled tasks, executors
 * started by the application) can be attributed too.
 *
 * <p>Learning happens when a span starts as a direct child of an HTTP SERVER span the servlet hook
 * has tagged: the thread's context class loader at that moment is the web application's. To stay
 * safe with shared loaders, a loader is never used when
 *
 * <ul>
 *   <li>it is the system/platform loader, one of their ancestors, or this extension's loader;
 *   <li>it was seen with two different deployments (conflict);
 *   <li>it is an ancestor of a learned loader (web application loaders are leaves; their parents,
 *       e.g. Tomcat's "common" loader, are shared).
 * </ul>
 */
final class LearnedDeployments {

  private static final DeploymentInfo SHARED = new DeploymentInfo("-", null);

  // Weak keys: undeployed web applications' loaders can be collected.
  private final Map<ClassLoader, DeploymentInfo> learned =
      Collections.synchronizedMap(new WeakHashMap<>());

  LearnedDeployments() {
    markShared(ClassLoader.getSystemClassLoader());
    markShared(LearnedDeployments.class.getClassLoader());
  }

  DeploymentInfo lookup(ClassLoader classLoader) {
    if (classLoader == null) {
      return null;
    }
    DeploymentInfo info = learned.get(classLoader);
    return info == SHARED ? null : info;
  }

  void learn(ClassLoader classLoader, DeploymentInfo info) {
    if (classLoader == null) {
      return;
    }
    synchronized (learned) {
      DeploymentInfo existing = learned.get(classLoader);
      if (existing == SHARED || info.equals(existing)) {
        return;
      }
      if (existing != null && !sameDeployment(existing, info)) {
        learned.put(classLoader, SHARED);
        return;
      }
      learned.put(classLoader, info);
      for (ClassLoader parent = classLoader.getParent(); parent != null; parent = parent.getParent()) {
        learned.put(parent, SHARED);
      }
    }
  }

  private void markShared(ClassLoader classLoader) {
    for (ClassLoader cl = classLoader; cl != null; cl = cl.getParent()) {
      learned.put(cl, SHARED);
    }
  }

  private static boolean sameDeployment(DeploymentInfo a, DeploymentInfo b) {
    return a.name().equals(b.name()) && Objects.equals(a.module(), b.module());
  }
}
