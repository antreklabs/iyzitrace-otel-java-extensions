package com.iyzitrace.otel.ibmmq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.context.Context;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The fakes mirror the shapes the extension reads: the agent's request and message adapter, IBM
 * MQ's JMSMessage and its delegate holding the session (a property map). The real chain was checked
 * against agent 2.21.0 and com.ibm.mq.jakarta.client 9.4.1.0 in iyzitrace-poc's IBM MQ lab.
 */
class QueueManagerEndpointTest {

  static final class Session extends HashMap<String, Object> {}

  static class MessageImplBase {
    private final Session theSession;

    MessageImplBase(Session session) {
      this.theSession = session;
    }
  }

  static final class MessageImpl extends MessageImplBase {
    MessageImpl(Session session) {
      super(session);
    }
  }

  public static final class IbmMessage {
    private final Object delegate;

    IbmMessage(Object delegate) {
      this.delegate = delegate;
    }

    public Object getDelegate() {
      return delegate;
    }
  }

  static final class Adapter {
    private final Object message;

    Adapter(Object message) {
      this.message = message;
    }
  }

  public static final class Request {
    private final Object adapter;

    Request(Object adapter) {
      this.adapter = adapter;
    }

    public Object message() {
      return adapter;
    }
  }

  private static Request request(Map<String, Object> properties) {
    Session session = new Session();
    session.putAll(properties);
    return new Request(new Adapter(new IbmMessage(new MessageImpl(session))));
  }

  private static Map<String, Object> props(Object host, Object port) {
    Map<String, Object> m = new HashMap<>();
    m.put(QueueManagerEndpoint.HOST_NAME, host);
    m.put(QueueManagerEndpoint.PORT, port);
    return m;
  }

  @Test
  void readsTheConnectedHostAndPort() {
    QueueManagerEndpoint e = QueueManagerEndpoint.ofRequest(request(props("qm1/172.23.0.3", 1414)));
    assertEquals("qm1", e.address);
    assertEquals(1414L, e.port);
  }

  @Test
  void readsTheSessionOfIbmsInternalMessageToo() {
    Session session = new Session();
    session.putAll(props("qm2/172.23.0.2", 1414));
    QueueManagerEndpoint e = QueueManagerEndpoint.ofRequest(new Request(new Adapter(new MessageImpl(session))));
    assertEquals("qm2", e.address);
  }

  @Test
  void isRegisteredUnderTheAgentsShadedName() throws IOException {
    // The agent renames the extension's classes but not its META-INF/services files.
    String shaded =
        "META-INF/services/io.opentelemetry.javaagent.shaded.instrumentation.api.incubator.instrumenter.InstrumenterCustomizerProvider";
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(shaded)) {
      assertEquals(
          IbmMqInstrumenterCustomizerProvider.class.getName(),
          new String(in.readAllBytes(), StandardCharsets.UTF_8).trim());
    }
  }

  @Test
  void usesTheAddressWhenConnectedByAddress() {
    assertEquals("10.0.0.5", QueueManagerEndpoint.address("/10.0.0.5"));
    assertEquals("mq-host", QueueManagerEndpoint.address("mq-host"));
    assertNull(QueueManagerEndpoint.address(""));
    assertNull(QueueManagerEndpoint.address(null));
  }

  @Test
  void givesNothingWhenAPieceIsMissing() {
    assertNull(QueueManagerEndpoint.ofRequest(request(props(null, 1414))));
    assertNull(QueueManagerEndpoint.ofRequest(request(props("qm1/1.2.3.4", "1414"))));
    assertNull(QueueManagerEndpoint.ofRequest(new Request(new Adapter(new Object()))));
    assertNull(QueueManagerEndpoint.ofRequest(new Request(new Adapter(null))));
    assertNull(QueueManagerEndpoint.ofRequest(new Object()));
    assertNull(QueueManagerEndpoint.ofRequest(null));
  }

  @Test
  void theExtractorSetsServerAttributes() {
    AttributesBuilder attributes = Attributes.builder();
    new IbmMqInstrumenterCustomizerProvider.ServerAttributesExtractor()
        .onStart(attributes, Context.root(), request(props("qm2/172.23.0.2", 1415)));
    Attributes built = attributes.build();
    assertEquals("qm2", built.get(IbmMqInstrumenterCustomizerProvider.ServerAttributesExtractor.SERVER_ADDRESS));
    assertEquals(1415L, built.get(IbmMqInstrumenterCustomizerProvider.ServerAttributesExtractor.SERVER_PORT));
  }

  @Test
  void theExtractorLeavesOtherProvidersAlone() {
    AttributesBuilder attributes = Attributes.builder();
    new IbmMqInstrumenterCustomizerProvider.ServerAttributesExtractor()
        .onStart(attributes, Context.root(), new Request(new Adapter(new Object())));
    assertEquals(0, attributes.build().size());
  }
}
