package com.iyzitrace.otel.appserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URL;
import java.net.URLClassLoader;
import com.iyzitrace.otel.appserver.shared.DeploymentInfo;
import org.junit.jupiter.api.Test;

class LearnedDeploymentsTest {

  private final ClassLoader common = new URLClassLoader(new URL[0], null); // e.g. Tomcat's "common"
  private final ClassLoader catalogLoader = new URLClassLoader(new URL[0], common);
  private final ClassLoader pricingLoader = new URLClassLoader(new URL[0], common);
  private final DeploymentInfo catalog = new DeploymentInfo("catalog", null);
  private final DeploymentInfo pricing = new DeploymentInfo("pricing", null);

  @Test
  void learnsWebAppLoaders() {
    LearnedDeployments learned = new LearnedDeployments();
    learned.learn(catalogLoader, catalog);
    learned.learn(pricingLoader, pricing);

    assertEquals(catalog, learned.lookup(catalogLoader));
    assertEquals(pricing, learned.lookup(pricingLoader));
  }

  @Test
  void parentsOfLearnedLoadersAreShared() {
    LearnedDeployments learned = new LearnedDeployments();
    learned.learn(catalogLoader, catalog);
    learned.learn(common, pricing);

    assertNull(learned.lookup(common));
  }

  @Test
  void loaderSeenWithTwoDeploymentsIsShared() {
    LearnedDeployments learned = new LearnedDeployments();
    learned.learn(common, catalog);
    learned.learn(common, pricing);
    learned.learn(common, catalog);

    assertNull(learned.lookup(common));
  }

  @Test
  void sameDeploymentWithNewDetailsIsUpdated() {
    LearnedDeployments learned = new LearnedDeployments();
    learned.learn(catalogLoader, catalog);
    DeploymentInfo declared = new DeploymentInfo("catalog", null, null, "catalog-service", null);
    learned.learn(catalogLoader, declared);

    assertEquals(declared, learned.lookup(catalogLoader));
  }

  @Test
  void systemLoaderIsNeverLearned() {
    LearnedDeployments learned = new LearnedDeployments();
    learned.learn(ClassLoader.getSystemClassLoader(), catalog);
    learned.learn(ClassLoader.getPlatformClassLoader(), catalog);

    assertNull(learned.lookup(ClassLoader.getSystemClassLoader()));
    assertNull(learned.lookup(ClassLoader.getPlatformClassLoader()));
    assertNull(learned.lookup(null));
  }
}
