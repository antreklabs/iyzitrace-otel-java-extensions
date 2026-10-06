package com.iyzitrace.otel.appserver;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import com.iyzitrace.otel.appserver.shared.VendorResolvers;

/**
 * Registers the span and log record processors with the agent's SDK.
 *
 * <p>Disable with {@code -Dotel.instrumentation.appserver-deployment.enabled=false}; the same flag
 * turns off the servlet hooks in {@link ServletInstrumentationModule}.
 */
public final class DeploymentCustomizerProvider implements AutoConfigurationCustomizerProvider {

  static final String ENABLED_PROPERTY = "otel.instrumentation.appserver-deployment.enabled";

  @Override
  public void customize(AutoConfigurationCustomizer customizer) {
    DeploymentAttribution attribution =
        new DeploymentAttribution(new VendorResolvers(), new LearnedDeployments());
    customizer.addTracerProviderCustomizer(
        (builder, config) ->
            enabled(config) ? builder.addSpanProcessor(new DeploymentSpanProcessor(attribution)) : builder);
    customizer.addLoggerProviderCustomizer(
        (builder, config) ->
            enabled(config)
                ? builder.addLogRecordProcessor(new DeploymentLogRecordProcessor(attribution))
                : builder);
  }

  private static boolean enabled(ConfigProperties config) {
    return config.getBoolean(ENABLED_PROPERTY, true);
  }
}
