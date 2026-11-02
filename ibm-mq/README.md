# ibm-mq

The OpenTelemetry Java agent traces IBM MQ through its JMS instrumentations, but its JMS spans say
only `messaging.system=jms` and the queue name: nothing tells which queue manager a message went
to. With several queue managers, every one of them looks the same to a backend, and a queue with
the same name on two queue managers is one queue.

This extension adds the queue manager's address to those spans, as the semantic conventions'
[messaging attributes](https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/) say:

| Attribute | Example | From |
|---|---|---|
| `server.address` | `qm1` | the host the connection actually reached (also with a connection name list), as the application named it; its IP address if the application connected by address |
| `server.port` | `1414` | the port of that host |

It applies to every span of the agent's `jms-1.1`, `jms-3.0` and `spring-jms` instrumentations
(send, receive and process) whose message comes from the IBM MQ client; messages of other JMS
providers are left as they are.

## How it works

It registers an `InstrumenterCustomizerProvider` that adds an attributes extractor to the agent's
JMS instrumenters. The extractor reads the session the IBM MQ client keeps on every message it
creates or delivers, and from it the connection's `XMSC_WMQ_HOST_NAME` and `XMSC_WMQ_PORT`. It
reads these by reflection, so it needs no IBM MQ jar. A message whose objects do not have that
shape gets no attributes; the span is left as the agent made it.

## Use

Put the jar next to the agent's other extensions and point the agent at the folder:

```text
-Dotel.javaagent.extensions=/opt/otel/extensions
```

Nothing changes in the application or its server.

## Compatibility

| | Version |
|---|---|
| OpenTelemetry Java agent | 2.21.0, verified; it needs the agent's `InstrumenterCustomizerProvider` API, so older agents may lack it (not checked) |
| IBM MQ JMS client | `com.ibm.mq.jakarta.client` 9.4.1.0 (Jakarta JMS 3), verified; `com.ibm.mq.allclient` (javax JMS) is expected to work the same way, not verified |
| Queue manager | any; verified with IBM MQ 9.4.5.1 |

Verified on WildFly 41 with an application that sends and an application that reads with a message
listener, against two queue managers. Every send, receive and process span carried the address and
port of its own queue manager.

## Limits

- The IBM MQ classes for Java (`MQQueueManager`, `MQQueue`), IBM's non-JMS API, are not
  instrumented by the agent at all; this extension does not change that.
- IBM's JMS client reports a received message's destination as the sender named it. A message put
  to a remote queue on one queue manager arrives on another under the sender's queue name.
