package com.iyzitrace.otel.appserver.shared;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Reads the service an application declares for itself, in the MicroProfile Telemetry format:
 *
 * <pre>
 * otel.service.name=order-service
 * otel.resource.attributes=service.namespace=commerce
 * </pre>
 *
 * {@code otel.service.name} wins over {@code service.name} inside {@code otel.resource.attributes};
 * other resource attributes are ignored.
 */
public final class DeclaredService {

  /** Location inside a JAR or a WAR's {@code WEB-INF/classes}. */
  public static final String CONFIG_RESOURCE = "META-INF/microprofile-config.properties";

  /**
   * Returns {serviceName, serviceNamespace} (either may be null), or {@code null} if the stream is
   * missing or declares neither. Closes the stream.
   */
  public static String[] read(InputStream in) {
    if (in == null) {
      return null;
    }
    Properties properties = new Properties();
    try (InputStream stream = in) {
      properties.load(stream);
    } catch (IOException | RuntimeException e) {
      return null;
    }
    String serviceName = trimToNull(properties.getProperty("otel.service.name"));
    String serviceNamespace = null;
    String resourceAttributes = properties.getProperty("otel.resource.attributes");
    if (resourceAttributes != null) {
      for (String pair : resourceAttributes.split(",")) {
        int eq = pair.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        String key = pair.substring(0, eq).trim();
        String value = trimToNull(pair.substring(eq + 1));
        if ("service.namespace".equals(key)) {
          serviceNamespace = value;
        } else if ("service.name".equals(key) && serviceName == null) {
          serviceName = value;
        }
      }
    }
    return serviceName == null && serviceNamespace == null
        ? null
        : new String[] {serviceName, serviceNamespace};
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private DeclaredService() {}
}
