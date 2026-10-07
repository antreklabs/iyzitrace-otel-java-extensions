package com.iyzitrace.lab.inventory;

import jakarta.annotation.Resource;
import jakarta.batch.runtime.BatchRuntime;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Exercises the WildFly subsystems the other endpoints don't, so their wildfly_* metrics move:
 * an HTTP session (undertow sessions), the default managed executor (ee) and a batch job (batch).
 */
@Path("/restock")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class RestockResource {

  private static final Logger LOG = Logger.getLogger(RestockResource.class.getName());

  /** Short, so sessions expire while the lab runs and the expired-sessions counter moves too. */
  private static final int SESSION_TIMEOUT_SECONDS = 60;

  @Resource private ManagedExecutorService executor;

  @Context private HttpServletRequest request;

  @POST
  @Path("/{sku}")
  public Map<String, Object> restock(@PathParam("sku") String sku) throws Exception {
    HttpSession session = request.getSession(true);
    session.setMaxInactiveInterval(SESSION_TIMEOUT_SECONDS);

    Future<Integer> quantity = executor.submit(() -> 5 + Math.floorMod(sku.hashCode(), 20));
    int units = quantity.get(5, TimeUnit.SECONDS);

    Properties parameters = new Properties();
    parameters.setProperty("sku", sku);
    parameters.setProperty("units", Integer.toString(units));
    long executionId = BatchRuntime.getJobOperator().start("restock", parameters);

    LOG.info("Restock of " + units + " x " + sku + " started as batch execution " + executionId);
    return Map.of("sku", sku, "units", units, "batchExecution", executionId);
  }
}
