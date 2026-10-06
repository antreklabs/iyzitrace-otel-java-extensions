package com.iyzitrace.lab.catalog;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import java.io.IOException;

/** GET /catalog/status is answered by this filter alone; no servlet runs (like Struts 2 or Wicket). */
@WebFilter("/status")
public class StatusFilter implements Filter {

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException {
    response.setContentType("text/plain");
    response.getWriter().write("catalog ok");
  }
}
