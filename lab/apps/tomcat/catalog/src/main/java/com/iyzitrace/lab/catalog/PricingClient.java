package com.iyzitrace.lab.catalog;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Calls pricing.war on the same Tomcat over HTTP, so one trace crosses two web applications. */
final class PricingClient {

  private static final String BASE_URL = System.getProperty("lab.internal.base-url", "http://localhost:8080");

  // HTTP/1.1: the default h2c upgrade exchange never reaches a servlet.
  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(2))
          .build();

  static HttpResponse<String> price(String item) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(BASE_URL + "/pricing/price?item=" + URLEncoder.encode(item, StandardCharsets.UTF_8)))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private PricingClient() {}
}
