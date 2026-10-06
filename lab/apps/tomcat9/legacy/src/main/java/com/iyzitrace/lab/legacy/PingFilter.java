package com.iyzitrace.lab.legacy;

import java.io.IOException;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;

/** javax.servlet filter answering GET /legacy/ping on its own. */
@WebFilter("/ping")
public class PingFilter implements Filter {

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException {
    response.setContentType("text/plain");
    response.getWriter().write("pong");
  }
}
