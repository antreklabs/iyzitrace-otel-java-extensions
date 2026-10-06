package com.iyzitrace.otel.appserver;

import static com.iyzitrace.otel.appserver.shared.DeploymentAttributes.CONTEXT_ROOT;
import static com.iyzitrace.otel.appserver.shared.DeploymentAttributes.DECLARED_SERVICE_NAME;
import static com.iyzitrace.otel.appserver.shared.DeploymentAttributes.DECLARED_SERVICE_NAMESPACE;
import static com.iyzitrace.otel.appserver.shared.DeploymentAttributes.DEPLOYMENT_NAME;
import static com.iyzitrace.otel.appserver.shared.DeploymentAttributes.MODULE;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadableSpan;
import com.iyzitrace.otel.appserver.shared.DeploymentInfo;
import com.iyzitrace.otel.appserver.shared.VendorResolvers;

/**
 * Decides the deployment attributes for a new span or log record.
 *
 * <ol>
 *   <li>A vendor resolver that recognises the thread context class loader wins: it reflects where
 *       the code is running right now, including calls from one deployment or module into another.
 *   <li>Otherwise, with an attributed parent span in this JVM, everything is inherited from it.
 *   <li>Otherwise (no parent, e.g. a scheduled task), a class loader learned earlier from requests
 *       is used; see {@link LearnedDeployments}.
 * </ol>
 *
 * Within the same deployment, the context root (only known by the servlet layer) is inherited from
 * the parent, and so is the declared service when the current module declares none: an EJB jar of
 * an EAR then reports the service its web module declared.
 */
final class DeploymentAttribution {

  private final VendorResolvers vendors;
  private final LearnedDeployments learned;

  /**
   * Once a vendor resolver has recognised a deployment, this server is covered by it and learning
   * is switched off: on such a server a loader the vendor doesn't recognise (e.g. a WildFly module)
   * is never a deployment's.
   */
  private volatile boolean vendorSeen;

  DeploymentAttribution(VendorResolvers vendors, LearnedDeployments learned) {
    this.vendors = vendors;
    this.learned = learned;
  }

  /** For spans: may also learn the class loader of a web application from its SERVER span. */
  Attributes forSpan(Context parentContext) {
    return resolve(parentContext, true);
  }

  Attributes forLogRecord(Context context) {
    return resolve(context, false);
  }

  private Attributes resolve(Context parentContext, boolean mayLearn) {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    ReadableSpan parent = localParent(parentContext);
    String parentName = parent == null ? null : parent.getAttribute(DEPLOYMENT_NAME);

    DeploymentInfo current = vendors.resolve(classLoader);
    if (current != null) {
      vendorSeen = true;
    } else if (!vendorSeen) {
      if (parentName == null) {
        current = learned.lookup(classLoader);
      } else if (mayLearn && parent.getKind() == SpanKind.SERVER) {
        learned.learn(classLoader, fromSpan(parent));
      }
    }
    return attributes(current, parent);
  }

  static Attributes attributes(DeploymentInfo current, ReadableSpan parent) {
    String parentName = parent == null ? null : parent.getAttribute(DEPLOYMENT_NAME);

    String name;
    String module;
    String serviceName;
    String serviceNamespace;
    if (current != null) {
      name = current.name();
      module = current.module();
      serviceName = current.serviceName();
      serviceNamespace = current.serviceNamespace();
    } else if (parentName != null) {
      name = parentName;
      module = parent.getAttribute(MODULE);
      serviceName = parent.getAttribute(DECLARED_SERVICE_NAME);
      serviceNamespace = parent.getAttribute(DECLARED_SERVICE_NAMESPACE);
    } else {
      return Attributes.empty();
    }

    String contextRoot = null;
    if (name.equals(parentName)) {
      contextRoot = parent.getAttribute(CONTEXT_ROOT);
      if (serviceName == null && serviceNamespace == null) {
        serviceName = parent.getAttribute(DECLARED_SERVICE_NAME);
        serviceNamespace = parent.getAttribute(DECLARED_SERVICE_NAMESPACE);
      }
    }

    AttributesBuilder attributes = Attributes.builder().put(DEPLOYMENT_NAME, name);
    putIfNotNull(attributes, MODULE, module);
    putIfNotNull(attributes, CONTEXT_ROOT, contextRoot);
    putIfNotNull(attributes, DECLARED_SERVICE_NAME, serviceName);
    putIfNotNull(attributes, DECLARED_SERVICE_NAMESPACE, serviceNamespace);
    return attributes.build();
  }

  /** What a SERVER span tagged by the servlet hook says about its web application. */
  private static DeploymentInfo fromSpan(ReadableSpan span) {
    return new DeploymentInfo(
        span.getAttribute(DEPLOYMENT_NAME),
        span.getAttribute(MODULE),
        null,
        span.getAttribute(DECLARED_SERVICE_NAME),
        span.getAttribute(DECLARED_SERVICE_NAMESPACE));
  }

  /** Parent span in this JVM, or {@code null} for root spans and spans with a remote parent. */
  static ReadableSpan localParent(Context parentContext) {
    Span parent = Span.fromContextOrNull(parentContext);
    if (parent instanceof ReadableSpan && !parent.getSpanContext().isRemote()) {
      return (ReadableSpan) parent;
    }
    return null;
  }

  private static void putIfNotNull(
      AttributesBuilder attributes, AttributeKey<String> key, String value) {
    if (value != null) {
      attributes.put(key, value);
    }
  }
}
