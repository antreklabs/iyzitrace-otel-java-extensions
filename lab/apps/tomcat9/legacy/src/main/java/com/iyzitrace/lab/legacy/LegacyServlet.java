package com.iyzitrace.lab.legacy;

import java.io.IOException;
import java.util.logging.Logger;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** javax.servlet (Tomcat 9): GET /legacy/hello. */
@WebServlet("/hello")
public class LegacyServlet extends HttpServlet {

  private static final Logger LOG = Logger.getLogger(LegacyServlet.class.getName());

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    LOG.info("Hello from a javax.servlet application");
    resp.setContentType("text/plain");
    resp.getWriter().write("hello from legacy");
  }
}
