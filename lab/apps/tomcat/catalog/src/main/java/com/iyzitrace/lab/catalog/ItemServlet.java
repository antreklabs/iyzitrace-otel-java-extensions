package com.iyzitrace.lab.catalog;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.logging.Logger;

/** GET /catalog/items/{id}: asks pricing.war for the price. */
@WebServlet("/items/*")
public class ItemServlet extends HttpServlet {

  private static final Logger LOG = Logger.getLogger(ItemServlet.class.getName());

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String item = req.getPathInfo() == null ? "unknown" : req.getPathInfo().substring(1);
    HttpResponse<String> price;
    try {
      price = PricingClient.price(item);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      resp.sendError(503);
      return;
    }
    if (price.statusCode() != 200) {
      LOG.warning("Pricing failed for " + item + ": HTTP " + price.statusCode());
      resp.sendError(502, "pricing returned " + price.statusCode());
      return;
    }
    LOG.info("Item " + item + " costs " + price.body());
    resp.setContentType("application/json");
    resp.getWriter().write("{\"item\":\"" + item + "\",\"price\":" + price.body() + "}");
  }
}
