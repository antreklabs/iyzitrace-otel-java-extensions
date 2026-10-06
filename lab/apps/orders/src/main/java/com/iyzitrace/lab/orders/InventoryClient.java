package com.iyzitrace.lab.orders;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Calls inventory.war over HTTP on the same server. The agent propagates the trace, so one trace
 * crosses two deployments inside one JVM.
 */
@ApplicationScoped
public class InventoryClient {

  private static final String BASE_URL = System.getProperty("lab.internal.base-url", "http://localhost:8080");

  // HTTP/1.1: the default h2c upgrade exchange is answered by Undertow and never reaches a servlet.
  private final HttpClient client =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(2))
          .build();

  int available(String sku) {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(BASE_URL + "/inventory/api/stock/" + URLEncoder.encode(sku, StandardCharsets.UTF_8)))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    HttpResponse<String> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new InventoryUnavailableException("inventory unreachable: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InventoryUnavailableException("interrupted");
    }
    if (response.statusCode() != 200) {
      throw new InventoryUnavailableException("inventory returned HTTP " + response.statusCode());
    }
    try (JsonReader reader = Json.createReader(new StringReader(response.body()))) {
      JsonObject stock = reader.readObject();
      return stock.getInt("available");
    }
  }

  static final class InventoryUnavailableException extends RuntimeException {
    InventoryUnavailableException(String message) {
      super(message);
    }
  }
}
