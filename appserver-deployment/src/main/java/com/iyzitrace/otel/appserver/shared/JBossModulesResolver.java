package com.iyzitrace.otel.appserver.shared;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * WildFly and JBoss EAP: every deployment has its own JBoss Modules class loader, named after the
 * module: {@code deployment.orders.war} for a top-level deployment and
 * {@code deployment.shop.ear.shop-web.war} for a module inside an EAR. While deployment code runs,
 * WildFly sets that loader as the thread context class loader (servlet requests, EJB invocations,
 * timers, MDBs, managed executors). Only {@code ClassLoader#getName()} is used, no WildFly API.
 *
 * <p>The module's own {@code META-INF/microprofile-config.properties} supplies the declared service;
 * see {@link #findConfig}.
 */
public final class JBossModulesResolver implements VendorResolver {

  private static final String MODULE_PREFIX = "deployment.";
  private static final String EAR_SEPARATOR = ".ear.";
  private static final int MAX_CACHE_SIZE = 1024;

  /** Cache marker for loader names that are not deployments (ConcurrentHashMap rejects nulls). */
  private static final DeploymentInfo NOT_A_DEPLOYMENT = new DeploymentInfo("-", null);

  /**
   * Keyed by loader name. The entry remembers its loader weakly: a redeployment reuses the module
   * name with a new loader (and possibly a new config file), which must not hit the old entry.
   */
  private final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();

  @Override
  public DeploymentInfo resolve(ClassLoader classLoader) {
    String loaderName = classLoader.getName();
    if (loaderName == null || !loaderName.startsWith(MODULE_PREFIX)) {
      return null;
    }
    Entry entry = cache.get(loaderName);
    if (entry == null || entry.loader.get() != classLoader) {
      DeploymentInfo info = parse(loaderName);
      info = info == null ? NOT_A_DEPLOYMENT : withDeclaredService(info, classLoader);
      entry = new Entry(classLoader, info);
      if (cache.size() < MAX_CACHE_SIZE || cache.containsKey(loaderName)) {
        cache.put(loaderName, entry);
      }
    }
    return entry.info == NOT_A_DEPLOYMENT ? null : entry.info;
  }

  /**
   * Parses a JBoss Modules deployment module name. Returns {@code null} for non-deployment modules
   * such as {@code org.jboss.resteasy.resteasy-core}.
   */
  public static DeploymentInfo parse(String moduleName) {
    if (moduleName == null || !moduleName.startsWith(MODULE_PREFIX)) {
      return null;
    }
    String name = moduleName.substring(MODULE_PREFIX.length());
    int slot = name.indexOf(':');
    if (slot >= 0) {
      name = name.substring(0, slot);
    }
    if (name.isEmpty()) {
      return null;
    }
    int ear = name.indexOf(EAR_SEPARATOR);
    if (ear > 0) {
      String earName = name.substring(0, ear + EAR_SEPARATOR.length() - 1);
      String module = name.substring(ear + EAR_SEPARATOR.length());
      return new DeploymentInfo(earName, module.isEmpty() ? null : module);
    }
    return new DeploymentInfo(name, null);
  }

  static DeploymentInfo withDeclaredService(DeploymentInfo info, ClassLoader classLoader) {
    URL config = findConfig(info, classLoader);
    if (config == null) {
      return info;
    }
    String[] declared;
    try {
      declared = DeclaredService.read(config.openStream());
    } catch (IOException | RuntimeException e) {
      return info;
    }
    return declared == null ? info : info.withService(declared[0], declared[1]);
  }

  /**
   * A deployment loader also sees config files of other modules (an EAR's EJB jars, ear/lib, jars in
   * WEB-INF/lib), so pick by URL: the module's own classes first, then its own libraries, then
   * anything else in the same top-level deployment (e.g. ear/lib). Files from outside the
   * deployment are ignored. WildFly URLs contain the deployment path, e.g.
   * {@code vfs:/content/shop.ear/shop-web.war/WEB-INF/classes/META-INF/...}.
   */
  static URL findConfig(DeploymentInfo info, ClassLoader classLoader) {
    List<URL> candidates;
    try {
      candidates = Collections.list(classLoader.getResources(DeclaredService.CONFIG_RESOURCE));
    } catch (IOException | RuntimeException e) {
      return null;
    }
    String module = "/" + (info.module() != null ? info.module() : info.name()) + "/";
    String deployment = "/" + info.name() + "/";
    URL ownLibrary = null;
    URL sameDeployment = null;
    for (URL url : candidates) {
      String path = url.toString();
      if (path.contains(module)) {
        boolean library = path.contains("/WEB-INF/lib/") || path.contains(".jar!/");
        if (!library) {
          return url;
        }
        if (ownLibrary == null) {
          ownLibrary = url;
        }
      } else if (sameDeployment == null && path.contains(deployment)) {
        sameDeployment = url;
      }
    }
    return ownLibrary != null ? ownLibrary : sameDeployment;
  }

  private static final class Entry {
    final WeakReference<ClassLoader> loader;
    final DeploymentInfo info;

    Entry(ClassLoader loader, DeploymentInfo info) {
      this.loader = new WeakReference<>(loader);
      this.info = info;
    }
  }
}
