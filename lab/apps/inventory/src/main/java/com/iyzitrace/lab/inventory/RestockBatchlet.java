package com.iyzitrace.lab.inventory;

import jakarta.batch.api.AbstractBatchlet;
import jakarta.batch.api.BatchProperty;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.logging.Logger;

/** The single step of the "restock" batch job (META-INF/batch-jobs/restock.xml). */
@Named("restockBatchlet")
@Dependent
public class RestockBatchlet extends AbstractBatchlet {

  private static final Logger LOG = Logger.getLogger(RestockBatchlet.class.getName());

  @Inject
  @BatchProperty
  private String sku;

  @Inject
  @BatchProperty
  private String units;

  @Override
  public String process() throws InterruptedException {
    Thread.sleep(20);
    LOG.info("Restocked " + units + " x " + sku);
    return "COMPLETED";
  }
}
