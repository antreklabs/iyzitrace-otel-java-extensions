package com.iyzitrace.otel.appserver;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;

/** Same attribution as {@link DeploymentSpanProcessor}, for log records bridged by the agent. */
final class DeploymentLogRecordProcessor implements LogRecordProcessor {

  private final DeploymentAttribution attribution;

  DeploymentLogRecordProcessor(DeploymentAttribution attribution) {
    this.attribution = attribution;
  }

  @Override
  public void onEmit(Context context, ReadWriteLogRecord logRecord) {
    Attributes attributes = attribution.forLogRecord(context);
    if (!attributes.isEmpty()) {
      logRecord.setAllAttributes(attributes);
    }
  }
}
