package com.iyzitrace.lab.pricing;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.logging.Logger;

/** GET /pricing/price?item=x. Declares no service name: named after its context root. */
@WebServlet("/price")
public class PriceServlet extends HttpServlet {

  private static final Logger LOG = Logger.getLogger(PriceServlet.class.getName());

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    String item = String.valueOf(req.getParameter("item"));
    if ("broken".equals(item)) {
      LOG.severe("No price for " + item);
      resp.sendError(500, "no price for " + item);
      return;
    }
    resp.setContentType("text/plain");
    resp.getWriter().write(String.valueOf(100 + Math.floorMod(item.hashCode(), 900)));
  }
}
