package com.iyzitrace.lab.reports;

import jakarta.annotation.Resource;
import jakarta.ejb.Schedule;
import jakarta.ejb.Singleton;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Standalone EJB jar with a timer: no HTTP request, no context root, no http.route. The timer
 * method becomes a root span through the agent's {@code methods} instrumentation.
 */
@Singleton
public class ReportJob {

  private static final Logger LOG = Logger.getLogger(ReportJob.class.getName());

  @Resource(lookup = "java:jboss/datasources/ExampleDS")
  DataSource dataSource;

  @Schedule(hour = "*", minute = "*", second = "*/15", persistent = false)
  public void run() {
    try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS ORDERS ("
              + "ID BIGINT AUTO_INCREMENT PRIMARY KEY, SKU VARCHAR(64) NOT NULL, QTY INT NOT NULL, "
              + "CREATED_AT TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
      try (ResultSet rs = s.executeQuery("SELECT COUNT(*), COALESCE(SUM(QTY), 0) FROM ORDERS")) {
        rs.next();
        LOG.info("Order report: " + rs.getLong(1) + " orders, " + rs.getLong(2) + " items");
      }
    } catch (SQLException e) {
      LOG.severe("Order report failed: " + e.getMessage());
    }
  }
}
