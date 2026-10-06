package com.iyzitrace.otel.appserver.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Directory layouts mimic WildFly's VFS paths, e.g. /content/shop.ear/shop-web.war/WEB-INF/classes. */
class JBossModulesResolverTest {

  @TempDir Path content;

  // ---- module names ----

  @Test
  void topLevelDeployments() {
    assertEquals(new DeploymentInfo("orders.war", null), JBossModulesResolver.parse("deployment.orders.war"));
    assertEquals(new DeploymentInfo("reports.jar", null), JBossModulesResolver.parse("deployment.reports.jar"));
  }

  @Test
  void earModules() {
    assertEquals(new DeploymentInfo("shop.ear", null), JBossModulesResolver.parse("deployment.shop.ear"));
    assertEquals(
        new DeploymentInfo("shop.ear", "shop-web.war"),
        JBossModulesResolver.parse("deployment.shop.ear.shop-web.war"));
    assertEquals(
        new DeploymentInfo("shop.ear", "shop-ejb.jar"),
        JBossModulesResolver.parse("deployment.shop.ear.shop-ejb.jar"));
  }

  @Test
  void legacySlotSuffixIsIgnored() {
    assertEquals(new DeploymentInfo("orders.war", null), JBossModulesResolver.parse("deployment.orders.war:main"));
  }

  @Test
  void nonDeploymentModules() {
    assertNull(JBossModulesResolver.parse("org.jboss.resteasy.resteasy-core"));
    assertNull(JBossModulesResolver.parse("app"));
    assertNull(JBossModulesResolver.parse("deployment."));
    assertNull(JBossModulesResolver.parse(null));
  }

  @Test
  void resolvesOnlyDeploymentLoaders() throws IOException {
    JBossModulesResolver resolver = new JBossModulesResolver();
    assertEquals(new DeploymentInfo("inventory.war", null), resolver.resolve(loader("deployment.inventory.war")));
    assertNull(resolver.resolve(loader("org.wildfly.extension.undertow")));
    assertNull(resolver.resolve(new URLClassLoader(new URL[0], null))); // unnamed, e.g. Tomcat
  }

  // ---- declared service ----

  @Test
  void declaredService() throws IOException {
    Path classes =
        config(
            "orders.war/WEB-INF/classes",
            "otel.service.name=order-service\notel.resource.attributes=service.namespace=commerce\n");

    assertEquals(
        new DeploymentInfo("orders.war", null, null, "order-service", "commerce"),
        new JBossModulesResolver().resolve(loader("deployment.orders.war", classes)));
  }

  @Test
  void ownModuleBeatsOtherModulesOfTheSameEar() throws IOException {
    Path ejb = config("shop.ear/shop-ejb.jar", "otel.service.name=from-ejb\n");
    Path earLib = config("shop.ear/lib/common.jar", "otel.service.name=from-ear-lib\n");
    Path web = config("shop.ear/shop-web.war/WEB-INF/classes", "otel.service.name=from-web\n");

    assertEquals("from-web", resolve("deployment.shop.ear.shop-web.war", ejb, earLib, web).serviceName());
    assertEquals("from-ejb", resolve("deployment.shop.ear.shop-ejb.jar", earLib, ejb).serviceName());
  }

  @Test
  void earLevelConfigIsTheFallbackForModules() throws IOException {
    Path earLib = config("shop.ear/lib/common.jar", "otel.service.name=shop\n");
    Path ejb = Files.createDirectories(content.resolve("shop.ear/shop-ejb.jar"));

    assertEquals("shop", resolve("deployment.shop.ear.shop-ejb.jar", earLib, ejb).serviceName());
  }

  @Test
  void ownClassesBeatOwnLibraries() throws IOException {
    Path library = config("orders.war/WEB-INF/lib/some-library.jar", "otel.service.name=from-library\n");
    Path classes = config("orders.war/WEB-INF/classes", "otel.service.name=from-classes\n");

    assertEquals("from-classes", resolve("deployment.orders.war", library, classes).serviceName());
  }

  @Test
  void filesOutsideTheDeploymentAreIgnored() throws IOException {
    Path otherApp = config("other.war/WEB-INF/classes", "otel.service.name=other\n");
    Path classes = Files.createDirectories(content.resolve("orders.war/WEB-INF/classes"));

    assertNull(resolve("deployment.orders.war", otherApp, classes).serviceName());
  }

  @Test
  void redeploymentWithANewLoaderIsReread() throws IOException {
    JBossModulesResolver resolver = new JBossModulesResolver();
    Path v1 = config("v1/orders.war/WEB-INF/classes", "otel.service.name=orders-v1\n");
    Path v2 = config("v2/orders.war/WEB-INF/classes", "otel.service.name=orders-v2\n");

    assertEquals("orders-v1", resolver.resolve(loader("deployment.orders.war", v1)).serviceName());
    assertEquals("orders-v2", resolver.resolve(loader("deployment.orders.war", v2)).serviceName());
  }

  private Path config(String root, String contents) throws IOException {
    Path dir = content.resolve(root);
    Path file = dir.resolve(DeclaredService.CONFIG_RESOURCE);
    Files.createDirectories(file.getParent());
    Files.writeString(file, contents);
    return dir;
  }

  private static DeploymentInfo resolve(String loaderName, Path... roots) throws IOException {
    return new JBossModulesResolver().resolve(loader(loaderName, roots));
  }

  static ClassLoader loader(String name, Path... roots) throws IOException {
    URL[] urls = new URL[roots.length];
    for (int i = 0; i < roots.length; i++) {
      urls[i] = roots[i].toUri().toURL();
    }
    return new URLClassLoader(name, urls, null);
  }
}
