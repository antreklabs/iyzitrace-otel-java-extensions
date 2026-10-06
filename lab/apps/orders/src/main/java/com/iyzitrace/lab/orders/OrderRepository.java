package com.iyzitrace.lab.orders;

import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/** Plain JDBC against WildFly's built-in H2 ExampleDS, so the agent's JDBC instrumentation fires. */
@ApplicationScoped
public class OrderRepository {

  @Resource(lookup = "java:jboss/datasources/ExampleDS")
  DataSource dataSource;

  void createSchema() {
    try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS ORDERS ("
              + "ID BIGINT AUTO_INCREMENT PRIMARY KEY, SKU VARCHAR(64) NOT NULL, QTY INT NOT NULL, "
              + "CREATED_AT TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot create ORDERS table", e);
    }
  }

  long insert(String sku, int qty) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement("INSERT INTO ORDERS (SKU, QTY) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, sku);
      ps.setInt(2, qty);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot insert order", e);
    }
  }

  List<Map<String, Object>> findRecent(int limit) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement("SELECT ID, SKU, QTY FROM ORDERS ORDER BY ID DESC LIMIT ?")) {
      ps.setInt(1, limit);
      List<Map<String, Object>> rows = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", rs.getLong("ID"));
          row.put("sku", rs.getString("SKU"));
          row.put("qty", rs.getInt("QTY"));
          rows.add(row);
        }
      }
      return rows;
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot read orders", e);
    }
  }
}
