package com.iyzitrace.lab.catalog;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Background work outside any request: every 10 s, refresh a price. Tomcat has no vendor resolver,
 * so these spans are attributed through the class loader the extension learned from requests.
 */
@WebListener
public class RefreshScheduler implements ServletContextListener {

  private static final Logger LOG = Logger.getLogger(RefreshScheduler.class.getName());

  private ScheduledExecutorService executor;

  @Override
  public void contextInitialized(ServletContextEvent event) {
    // Threads inherit the creating thread's context class loader: the web application's.
    executor = Executors.newSingleThreadScheduledExecutor();
    executor.scheduleAtFixedRate(this::refresh, 10, 10, TimeUnit.SECONDS);
  }

  @Override
  public void contextDestroyed(ServletContextEvent event) {
    executor.shutdownNow();
  }

  private void refresh() {
    try {
      LOG.info("Price refresh: " + PricingClient.price("refresh").body());
    } catch (Exception e) {
      LOG.warning("Price refresh failed: " + e);
    }
  }
}
