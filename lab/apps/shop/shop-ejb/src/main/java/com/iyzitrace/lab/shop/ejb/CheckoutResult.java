package com.iyzitrace.lab.shop.ejb;

public class CheckoutResult {

  private final String sku;
  private final int qty;
  private final long priceInCents;
  private final int orderStatus;

  public CheckoutResult(String sku, int qty, long priceInCents, int orderStatus) {
    this.sku = sku;
    this.qty = qty;
    this.priceInCents = priceInCents;
    this.orderStatus = orderStatus;
  }

  public String getSku() {
    return sku;
  }

  public int getQty() {
    return qty;
  }

  public long getPriceInCents() {
    return priceInCents;
  }

  public int getOrderStatus() {
    return orderStatus;
  }
}
