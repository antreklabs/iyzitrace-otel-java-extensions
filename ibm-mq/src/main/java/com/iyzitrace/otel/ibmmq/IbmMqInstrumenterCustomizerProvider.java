package com.iyzitrace.otel.ibmmq;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.incubator.instrumenter.InstrumenterCustomizer;
import io.opentelemetry.instrumentation.api.incubator.instrumenter.InstrumenterCustomizerProvider;
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor;

/**
 * Adds {@code server.address} and {@code server.port} of the IBM MQ queue manager to the spans of
 * the agent's JMS instrumentations ({@code jms-1.1}, {@code jms-3.0}, {@code spring-jms}): send,
 * receive and process. Messages of other JMS providers are left as they are.
 */
public final class IbmMqInstrumenterCustomizerProvider implements InstrumenterCustomizerProvider {

  @Override
  public void customize(InstrumenterCustomizer customizer) {
    if (customizer.getInstrumentationName().contains("jms")) {
      customizer.addAttributesExtractor(new ServerAttributesExtractor());
    }
  }

  static final class ServerAttributesExtractor implements AttributesExtractor<Object, Object> {

    static final AttributeKey<String> SERVER_ADDRESS = AttributeKey.stringKey("server.address");
    static final AttributeKey<Long> SERVER_PORT = AttributeKey.longKey("server.port");

    @Override
    public void onStart(AttributesBuilder attributes, Context parentContext, Object request) {
      QueueManagerEndpoint endpoint = QueueManagerEndpoint.ofRequest(request);
      if (endpoint != null) {
        attributes.put(SERVER_ADDRESS, endpoint.address);
        attributes.put(SERVER_PORT, endpoint.port);
      }
    }

    @Override
    public void onEnd(
        AttributesBuilder attributes,
        Context context,
        Object request,
        Object response,
        Throwable error) {}
  }
}
