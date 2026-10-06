package com.iyzitrace.lab.shop.ejb;

import jakarta.ejb.EJB;
import jakarta.ejb.Stateless;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * EJB inside shop.ear. Its methods only show up as spans through the agent's {@code methods}
 * instrumentation, which yields the code.* attributes and nothing else (the original problem).
 */
@Stateless
public class CheckoutService {

  private static final Logger LOG = Logger.getLogger(CheckoutService.class.getName());
  private static final String BASE_URL = System.getProperty("lab.internal.base-url", "http://localhost:8080");
  // HTTP/1.1: the default h2c upgrade exchange is answered by Undertow and never reaches a servlet.
  private static final HttpClient CLIENT =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(2))
          .build();

  @EJB PricingService pricing;

  public CheckoutResult checkout(String sku, int qty) {
    long price = pricing.priceInCents(sku, qty);
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    BASE_URL
                        + "/orders/api/orders?sku="
                        + URLEncoder.encode(sku, StandardCharsets.UTF_8)
                        + "&qty="
                        + qty))
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    try {
      HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
      LOG.info("Checkout " + qty + " x " + sku + " (" + price + " cents): orders answered " + response.statusCode());
      return new CheckoutResult(sku, qty, price, response.statusCode());
    } catch (IOException e) {
      LOG.warning("Checkout of " + sku + " failed: " + e.getMessage());
      return new CheckoutResult(sku, qty, price, 503);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new CheckoutResult(sku, qty, price, 503);
    }
  }
}
