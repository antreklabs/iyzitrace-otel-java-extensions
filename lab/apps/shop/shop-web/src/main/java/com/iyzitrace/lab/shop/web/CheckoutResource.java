package com.iyzitrace.lab.shop.web;

import jakarta.ejb.EJB;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import com.iyzitrace.lab.shop.ejb.CheckoutResult;
import com.iyzitrace.lab.shop.ejb.CheckoutService;

/** shop-web.war -> shop-ejb.jar (same EAR) -> orders.war -> inventory.war. */
@Path("/checkout")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class CheckoutResource {

  @EJB CheckoutService checkout;

  @GET
  @Path("/{sku}")
  public Response checkout(@PathParam("sku") String sku, @QueryParam("qty") @DefaultValue("1") int qty) {
    CheckoutResult result = checkout.checkout(sku, qty);
    int status = result.getOrderStatus() == 201 ? 200 : 502;
    return Response.status(status).entity(result).build();
  }
}
