package com.iyzitrace.otel.appserver.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WebAppTaggerTest {

  @Test
  void genericContainerNamesTheAppAfterItsContextRoot() {
    assertEquals(
        new DeploymentInfo("catalog", null, "/catalog", null, null),
        WebAppTagger.webApp("/catalog", null, null));
    assertEquals(new DeploymentInfo("ROOT", null, "/", null, null), WebAppTagger.webApp("", null, null));
    assertEquals(
        new DeploymentInfo("shop#admin", null, "/shop/admin", null, null),
        WebAppTagger.webApp("/shop/admin", null, null));
  }

  @Test
  void declarationReadThroughTheServletContext() {
    assertEquals(
        new DeploymentInfo("catalog", null, "/catalog", "catalog-service", "commerce"),
        WebAppTagger.webApp("/catalog", new String[] {"catalog-service", "commerce"}, null));
  }

  @Test
  void vendorNamesWin() {
    DeploymentInfo vendor = new DeploymentInfo("shop.ear", "shop-web.war");
    assertEquals(
        new DeploymentInfo("shop.ear", "shop-web.war", "/shop", "from-war-file", null),
        WebAppTagger.webApp("/shop", new String[] {"from-war-file", null}, vendor));
  }

  @Test
  void vendorDeclarationWinsAsAPair() {
    DeploymentInfo vendor = new DeploymentInfo("orders.war", null, null, "from-vendor", null);
    assertEquals(
        new DeploymentInfo("orders.war", null, "/orders", "from-vendor", null),
        WebAppTagger.webApp("/orders", new String[] {"from-war-file", "ns"}, vendor));
  }
}
