package com.iyzitrace.lab.inventory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/** Random latency, and sku "broken" always fails, so traces show slow and error spans. */
@Path("/stock")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class StockResource {

  private static final Logger LOG = Logger.getLogger(StockResource.class.getName());

  @GET
  @Path("/{sku}")
  public Map<String, Object> stock(@PathParam("sku") String sku) throws InterruptedException {
    Thread.sleep(ThreadLocalRandom.current().nextInt(5, 60));
    if ("broken".equalsIgnoreCase(sku)) {
      LOG.severe("Stock lookup failed for " + sku);
      throw new IllegalStateException("stock backend failure for sku " + sku);
    }
    int available = Math.floorMod(sku.hashCode(), 10);
    LOG.info("Stock for " + sku + ": " + available);
    return Map.of("sku", sku, "available", available);
  }
}
