package com.iyzitrace.otel.appserver;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/** Tags every span with the deployment it was started in; see {@link DeploymentAttribution}. */
final class DeploymentSpanProcessor implements SpanProcessor {

  private final DeploymentAttribution attribution;

  DeploymentSpanProcessor(DeploymentAttribution attribution) {
    this.attribution = attribution;
  }

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    Attributes attributes = attribution.forSpan(parentContext);
    if (!attributes.isEmpty()) {
      span.setAllAttributes(attributes);
    }
  }

  @Override
  public boolean isStartRequired() {
    return true;
  }

  @Override
  public void onEnd(ReadableSpan span) {}

  @Override
  public boolean isEndRequired() {
    return false;
  }
}
