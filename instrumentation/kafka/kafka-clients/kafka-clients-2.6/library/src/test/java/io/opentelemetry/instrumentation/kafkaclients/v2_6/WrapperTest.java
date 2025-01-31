/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.kafkaclients.v2_6;

import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.equalTo;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.satisfies;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.kafka.internal.KafkaClientBaseTest;
import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension;
import io.opentelemetry.instrumentation.testing.junit.LibraryInstrumentationExtension;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.assertj.core.api.AbstractLongAssert;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WrapperTest extends KafkaClientBaseTest {

  @RegisterExtension
  static final InstrumentationExtension testing = LibraryInstrumentationExtension.create();

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testWrappers(boolean testHeaders) throws InterruptedException {
    KafkaTelemetry telemetry =
        KafkaTelemetry.builder(testing.getOpenTelemetry())
            .setCapturedHeaders(singletonList("test-message-header"))
            // TODO run tests both with and without experimental span attributes
            .setCaptureExperimentalSpanAttributes(true)
            .build();

    String greeting = "Hello Kafka!";
    Producer<Integer, String> wrappedProducer = telemetry.wrap(producer);

    testing.runWithSpan(
        "parent",
        () -> {
          ProducerRecord<Integer, String> producerRecord =
              new ProducerRecord<>(SHARED_TOPIC, greeting);
          if (testHeaders) {
            producerRecord
                .headers()
                .add("test-message-header", "test".getBytes(StandardCharsets.UTF_8));
          }
          wrappedProducer.send(
              producerRecord,
              (meta, ex) -> {
                if (ex == null) {
                  testing.runWithSpan("producer callback", () -> {});
                } else {
                  testing.runWithSpan("producer exception: " + ex, () -> {});
                }
              });
        });

    awaitUntilConsumerIsReady();
    Consumer<Integer, String> wrappedConsumer = telemetry.wrap(consumer);
    ConsumerRecords<?, ?> records = wrappedConsumer.poll(Duration.ofSeconds(10));
    assertThat(records.count()).isEqualTo(1);
    for (ConsumerRecord<?, ?> record : records) {
      assertThat(record.value()).isEqualTo(greeting);
      assertThat(record.key()).isNull();
    }

    testing.waitAndAssertTraces(
        trace -> {
          trace.hasSpansSatisfyingExactly(
              span -> {
                span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent();
              },
              span -> {
                span.hasName(SHARED_TOPIC + " send")
                    .hasKind(SpanKind.PRODUCER)
                    .hasParent(trace.getSpan(0))
                    .hasAttributesSatisfying(
                        equalTo(SemanticAttributes.MESSAGING_SYSTEM, "kafka"),
                        equalTo(SemanticAttributes.MESSAGING_DESTINATION_NAME, SHARED_TOPIC),
                        equalTo(SemanticAttributes.MESSAGING_DESTINATION_KIND, "topic"),
                        satisfies(
                            SemanticAttributes.MESSAGING_KAFKA_DESTINATION_PARTITION,
                            AbstractLongAssert::isNotNegative));
                if (testHeaders) {
                  span.hasAttributesSatisfying(
                      equalTo(
                          AttributeKey.stringArrayKey("messaging.header.test_message_header"),
                          Collections.singletonList("test")));
                }
              },
              span -> {
                span.hasName(SHARED_TOPIC + " receive")
                    .hasKind(SpanKind.CONSUMER)
                    .hasParent(trace.getSpan(1))
                    .hasAttributesSatisfying(
                        equalTo(SemanticAttributes.MESSAGING_SYSTEM, "kafka"),
                        equalTo(SemanticAttributes.MESSAGING_DESTINATION_NAME, SHARED_TOPIC),
                        equalTo(SemanticAttributes.MESSAGING_DESTINATION_KIND, "topic"),
                        equalTo(SemanticAttributes.MESSAGING_OPERATION, "receive"),
                        equalTo(
                            SemanticAttributes.MESSAGING_MESSAGE_PAYLOAD_SIZE_BYTES,
                            greeting.getBytes(StandardCharsets.UTF_8).length),
                        satisfies(
                            SemanticAttributes.MESSAGING_KAFKA_SOURCE_PARTITION,
                            AbstractLongAssert::isNotNegative),
                        satisfies(
                            AttributeKey.longKey("messaging.kafka.message.offset"),
                            AbstractLongAssert::isNotNegative));
                if (testHeaders) {
                  span.hasAttributesSatisfying(
                      equalTo(
                          AttributeKey.stringArrayKey("messaging.header.test_message_header"),
                          Collections.singletonList("test")));
                }
              },
              span -> {
                span.hasName("producer callback")
                    .hasKind(SpanKind.INTERNAL)
                    .hasParent(trace.getSpan(0));
              });
        });
  }
}
