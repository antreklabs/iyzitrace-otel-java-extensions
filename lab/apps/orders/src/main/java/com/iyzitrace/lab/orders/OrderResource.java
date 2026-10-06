package com.iyzitrace.lab.orders;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** HTTP in, HTTP out (to inventory.war), JDBC (ExampleDS) and logs: the four signals in one request. */
@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class OrderResource {

  private static final Logger LOG = Logger.getLogger(OrderResource.class.getName());

  @Inject OrderRepository repository;
  @Inject InventoryClient inventory;

  @GET
  public List<Map<String, Object>> recent() {
    return repository.findRecent(10);
  }

  @POST
  public Response create(@QueryParam("sku") String sku, @QueryParam("qty") @DefaultValue("1") int qty) {
    if (sku == null || sku.isBlank()) {
      return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "sku is required")).build();
    }
    int available;
    try {
      available = inventory.available(sku);
    } catch (InventoryClient.InventoryUnavailableException e) {
      LOG.warning("Inventory check failed for " + sku + ": " + e.getMessage());
      return Response.status(Response.Status.BAD_GATEWAY).entity(Map.of("error", e.getMessage())).build();
    }
    if (available < qty) {
      LOG.info("Rejected order for " + sku + ": requested " + qty + ", available " + available);
      return Response.status(Response.Status.CONFLICT)
          .entity(Map.of("sku", sku, "requested", qty, "available", available))
          .build();
    }
    long id = repository.insert(sku, qty);
    LOG.info("Created order " + id + " for " + qty + " x " + sku);
    return Response.status(Response.Status.CREATED).entity(Map.of("id", id, "sku", sku, "qty", qty)).build();
  }
}
