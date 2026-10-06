package com.iyzitrace.lab.orders;

import jakarta.annotation.PostConstruct;
import jakarta.ejb.Singleton;
import jakarta.ejb.Startup;
import jakarta.inject.Inject;

@Singleton
@Startup
public class SchemaInitializer {

  @Inject OrderRepository repository;

  @PostConstruct
  void init() {
    repository.createSchema();
  }
}
