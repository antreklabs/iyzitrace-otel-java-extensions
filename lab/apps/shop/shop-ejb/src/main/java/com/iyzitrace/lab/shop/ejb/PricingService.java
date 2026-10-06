package com.iyzitrace.lab.shop.ejb;

import jakarta.ejb.Stateless;

@Stateless
public class PricingService {

  public long priceInCents(String sku, int qty) {
    return (100L + Math.floorMod(sku.hashCode(), 900)) * qty;
  }
}
