package com.iyzitrace.otel.appserver;

import static com.iyzitrace.otel.appserver.shared.DeploymentAttributes.CONTEXT_ROOT;
import static com.iyzitrace.otel.appserver.shared.DeploymentAttributes.DECLARED_SERVICE_NAME;
import static com.iyzitrace.otel.appserver.shared.DeploymentAttributes.DEPLOYMENT_NAME;
import static com.iyzitrace.otel.appserver.shared.DeploymentAttributes.MODULE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import com.iyzitrace.otel.appserver.shared.VendorResolvers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** End to end through a real SDK; the servlet hook is simulated by setting its attributes. */
class DeploymentSpanProcessorTest {

  private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
  private SdkTracerProvider provider;
  private Tracer tracer;

  /** Like Tomcat's web application loaders: unnamed, so no vendor resolver recognises them. */
  private final ClassLoader catalogLoader = new URLClassLoader(new URL[0], null);

  @BeforeEach
  void setUp() {
    provider =
        SdkTracerProvider.builder()
            .addSpanProcessor(
                new DeploymentSpanProcessor(
                    new DeploymentAttribution(new VendorResolvers(), new LearnedDeployments())))
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    tracer = provider.get("test");
  }

  @AfterEach
  void tearDown() {
    provider.close();
  }

  @Test
  void vendorResolverRecognisesTheThreadContextClassLoader() {
    withContextClassLoader(named("deployment.orders.war"), () -> span("work", null));

    Attributes attributes = only("work");
    assertEquals("orders.war", attributes.get(DEPLOYMENT_NAME));
    assertNull(attributes.get(CONTEXT_ROOT));
  }

  @Test
  void childSpansInheritFromTheTaggedServerSpan() {
    withContextClassLoader(catalogLoader, this::catalogRequest);

    Attributes child = only("SELECT items");
    assertEquals("catalog", child.get(DEPLOYMENT_NAME));
    assertEquals("/catalog", child.get(CONTEXT_ROOT));
    assertEquals("catalog-service", child.get(DECLARED_SERVICE_NAME));
  }

  @Test
  void learnedLoaderAttributesWorkOutsideRequests() {
    withContextClassLoader(catalogLoader, this::catalogRequest);
    withContextClassLoader(catalogLoader, () -> span("scheduled refresh", null));

    Attributes scheduled = only("scheduled refresh");
    assertEquals("catalog", scheduled.get(DEPLOYMENT_NAME));
    assertEquals("catalog-service", scheduled.get(DECLARED_SERVICE_NAME));
    assertNull(scheduled.get(CONTEXT_ROOT)); // not inside a request
  }

  @Test
  void unknownLoaderOutsideRequestsGetsNothing() {
    withContextClassLoader(catalogLoader, () -> span("scheduled refresh", null));

    assertTrue(only("scheduled refresh").isEmpty());
  }

  @Test
  void learningIsOffOnceAVendorResolverRecognisedSomething() {
    withContextClassLoader(named("deployment.orders.war"), () -> span("work", null));
    withContextClassLoader(catalogLoader, this::catalogRequest);
    withContextClassLoader(catalogLoader, () -> span("scheduled refresh", null));

    assertEquals("catalog", only("SELECT items").get(DEPLOYMENT_NAME)); // still inherited
    assertTrue(only("scheduled refresh").isEmpty()); // but not learned
  }

  @Test
  void earModuleInheritsContextRootAndDeclarationFromItsWebModule() {
    Span server = tracer.spanBuilder("GET /shop").setSpanKind(SpanKind.SERVER).startSpan();
    server.setAttribute(DEPLOYMENT_NAME, "shop.ear");
    server.setAttribute(MODULE, "shop-web.war");
    server.setAttribute(CONTEXT_ROOT, "/shop");
    server.setAttribute(DECLARED_SERVICE_NAME, "shop-frontend");
    try (Scope ignored = server.makeCurrent()) {
      withContextClassLoader(named("deployment.shop.ear.shop-ejb.jar"), () -> span("CheckoutService.checkout", null));
    } finally {
      server.end();
    }

    Attributes ejb = only("CheckoutService.checkout");
    assertEquals("shop.ear", ejb.get(DEPLOYMENT_NAME));
    assertEquals("shop-ejb.jar", ejb.get(MODULE));
    assertEquals("/shop", ejb.get(CONTEXT_ROOT));
    assertEquals("shop-frontend", ejb.get(DECLARED_SERVICE_NAME));
  }

  @Test
  void remoteParentsAreNotInheritedFrom() {
    SpanContext remote =
        SpanContext.createFromRemoteParent(
            "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", TraceFlags.getSampled(), TraceState.getDefault());
    Context parent = Context.root().with(Span.wrap(remote));
    withContextClassLoader(catalogLoader, () -> span("GET /pricing", parent));

    assertTrue(only("GET /pricing").isEmpty());
  }

  /** A server span tagged the way the servlet hook does, with one child inside the request. */
  private void catalogRequest() {
    Span server = tracer.spanBuilder("GET /catalog/items").setSpanKind(SpanKind.SERVER).startSpan();
    server.setAttribute(DEPLOYMENT_NAME, "catalog");
    server.setAttribute(CONTEXT_ROOT, "/catalog");
    server.setAttribute(DECLARED_SERVICE_NAME, "catalog-service");
    try (Scope ignored = server.makeCurrent()) {
      span("SELECT items", null);
    } finally {
      server.end();
    }
  }

  private void span(String name, Context parent) {
    io.opentelemetry.api.trace.SpanBuilder builder = tracer.spanBuilder(name);
    if (parent != null) {
      builder.setParent(parent);
    }
    builder.startSpan().end();
  }

  private Attributes only(String spanName) {
    List<SpanData> spans = exporter.getFinishedSpanItems();
    SpanData match = null;
    for (SpanData span : spans) {
      if (span.getName().equals(spanName)) {
        match = span;
      }
    }
    if (match == null) {
      throw new AssertionError("no span " + spanName + " in " + spans);
    }
    return match.getAttributes();
  }

  private static ClassLoader named(String name) {
    return new URLClassLoader(name, new URL[0], null);
  }

  private static void withContextClassLoader(ClassLoader loader, Runnable runnable) {
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    thread.setContextClassLoader(loader);
    try {
      runnable.run();
    } finally {
      thread.setContextClassLoader(previous);
    }
  }
}
