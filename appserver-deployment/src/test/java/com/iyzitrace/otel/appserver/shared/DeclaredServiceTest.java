package com.iyzitrace.otel.appserver.shared;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DeclaredServiceTest {

  @Test
  void nameAndNamespace() {
    assertArrayEquals(
        new String[] {"order-service", "commerce"},
        DeclaredService.read(
            stream("otel.service.name=order-service\notel.resource.attributes=team=a, service.namespace=commerce\n")));
  }

  @Test
  void otelServiceNameWinsOverResourceAttribute() {
    assertArrayEquals(
        new String[] {"from-property", null},
        DeclaredService.read(
            stream("otel.service.name=from-property\notel.resource.attributes=service.name=from-attributes\n")));
  }

  @Test
  void serviceNameFromResourceAttributes() {
    assertArrayEquals(
        new String[] {"orders-api", null},
        DeclaredService.read(stream("otel.resource.attributes=service.name=orders-api\n")));
  }

  @Test
  void nothingDeclared() {
    assertNull(DeclaredService.read(null));
    assertNull(DeclaredService.read(stream("mp.config.something=1\notel.service.name=  \n")));
  }

  private static InputStream stream(String contents) {
    return new ByteArrayInputStream(contents.getBytes(StandardCharsets.ISO_8859_1));
  }
}
