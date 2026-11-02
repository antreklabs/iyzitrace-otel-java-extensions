package com.iyzitrace.otel.ibmmq;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * The address and port of the queue manager an IBM MQ JMS message belongs to, read from the
 * session the IBM MQ client keeps on every message it creates or delivers:
 *
 * <pre>
 *   agent request ─message()─► agent message adapter ─(field message)─► IBM JMSMessage
 *     ─getDelegate()─► IBM JmsMessageImpl ─(field theSession)─► IBM JmsSessionImpl (a Map)
 *     ─► XMSC_WMQ_HOST_NAME "qm1/10.0.0.5", XMSC_WMQ_PORT 1414
 * </pre>
 *
 * <p>For IBM's internal consumer and listener the adapter holds the JmsMessageImpl itself.
 *
 * <p>The host is the one the connection actually reached, also with a connection name list. Every
 * step is by reflection, so no IBM MQ, JMS or agent-internal class is needed at build time; any
 * step that does not fit gives no endpoint and the span stays as the agent made it.
 */
final class QueueManagerEndpoint {

  static final String HOST_NAME = "XMSC_WMQ_HOST_NAME";
  static final String PORT = "XMSC_WMQ_PORT";

  final String address;
  final long port;

  private QueueManagerEndpoint(String address, long port) {
    this.address = address;
    this.port = port;
  }

  private static final ClassValue<Method> MESSAGE = methodNamed("message");
  private static final ClassValue<Field> ADAPTED = fieldNamed("message");
  private static final ClassValue<Method> DELEGATE = methodNamed("getDelegate");
  private static final ClassValue<Field> SESSION = fieldNamed("theSession");

  /** The endpoint of the agent's JMS request ({@code MessageWithDestination}), or null. */
  static QueueManagerEndpoint ofRequest(Object request) {
    try {
      Object adapter = invoke(MESSAGE, request);
      Object message = read(ADAPTED, adapter);
      // The agent sees the application's JMSMessage, or for IBM's internal consumer and listener
      // the JmsMessageImpl itself, which holds the session directly.
      Object session = read(SESSION, message);
      if (session == null) {
        session = read(SESSION, invoke(DELEGATE, message));
      }
      return ofSession(session);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  static QueueManagerEndpoint ofSession(Object session) {
    if (!(session instanceof Map)) {
      return null;
    }
    Map<?, ?> properties = (Map<?, ?>) session;
    String address = address(properties.get(HOST_NAME));
    Object port = properties.get(PORT);
    if (address == null || !(port instanceof Number)) {
      return null;
    }
    return new QueueManagerEndpoint(address, ((Number) port).longValue());
  }

  /** {@code qm1/10.0.0.5} gives {@code qm1}; {@code /10.0.0.5} (connected by address) gives the address. */
  static String address(Object hostName) {
    if (!(hostName instanceof String)) {
      return null;
    }
    String value = ((String) hostName).trim();
    int slash = value.indexOf('/');
    String name = slash < 0 ? value : value.substring(0, slash);
    if (name.isEmpty() && slash >= 0) {
      name = value.substring(slash + 1);
    }
    return name.isEmpty() ? null : name;
  }

  // A missing member gives null rather than an exception: messages of other JMS providers go
  // through here too, on every span.
  private static Object invoke(ClassValue<Method> method, Object target)
      throws ReflectiveOperationException {
    Method m = target == null ? null : method.get(target.getClass());
    return m == null ? null : m.invoke(target);
  }

  private static Object read(ClassValue<Field> field, Object target) throws IllegalAccessException {
    Field f = target == null ? null : field.get(target.getClass());
    return f == null ? null : f.get(target);
  }

  private static ClassValue<Method> methodNamed(String name) {
    return new ClassValue<Method>() {
      @Override
      protected Method computeValue(Class<?> type) {
        try {
          Method method = type.getMethod(name);
          method.setAccessible(true);
          return method;
        } catch (NoSuchMethodException | RuntimeException e) {
          return null;
        }
      }
    };
  }

  private static ClassValue<Field> fieldNamed(String name) {
    return new ClassValue<Field>() {
      @Override
      protected Field computeValue(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
          try {
            Field field = c.getDeclaredField(name);
            field.setAccessible(true);
            return field;
          } catch (NoSuchFieldException e) {
            // look in the superclass
          } catch (RuntimeException e) {
            return null;
          }
        }
        return null;
      }
    };
  }
}
