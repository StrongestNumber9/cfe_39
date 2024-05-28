/*
   HDFS Data Ingestion for PTH_06 use CFE-39
   Copyright (C) 2022  Fail-Safe IT Solutions Oy

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.teragrep.cfe_39.consumers.kafka;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;

public class ReadCoordinator implements Runnable {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(ReadCoordinator.class);

    private final String queueTopic;
    private final Properties readerKafkaProperties;
    private final Consumer<List<RecordOffset>> callbackFunction;
    private boolean run = true;
    private final Map<TopicPartition, Long> hdfsStartOffsets;

    public ReadCoordinator(
            String queueTopic,
            Properties readerKafkaProperties,
            Consumer<List<RecordOffset>> callbackFunction,
            Map<TopicPartition, Long> hdfsStartOffsets)
    {
        this.queueTopic = queueTopic;
        this.readerKafkaProperties = readerKafkaProperties;
        this.callbackFunction = callbackFunction;
        this.hdfsStartOffsets = hdfsStartOffsets;
    }

    private KafkaReader createKafkaReader(Properties readerKafkaProperties,
                                          String topic,
                                          Consumer<List<RecordOffset>> callbackFunction,
                                          boolean useMockKafkaConsumer) {

        org.apache.kafka.clients.consumer.Consumer<byte[], byte[]> kafkaConsumer;
        if (useMockKafkaConsumer) { // Mock kafka consumer is enabled, create mock consumers with assigned partitions that are not overlapping with each other.
            String name = Thread.currentThread().getName(); // Use thread name to identify which thread is running the code.
            if (Objects.equals(name, "testConsumerTopic1")) {
                kafkaConsumer = MockKafkaConsumerFactoryTemp.getConsumer(1); // creates a Kafka MockConsumer that has the odd numbered partitions assigned to it.
            }else {
                kafkaConsumer = MockKafkaConsumerFactoryTemp.getConsumer(2); // creates a Kafka MockConsumer that has the even numbered partitions assigned to it.
            }
        } else { // Mock kafka consumer is disabled, subscribe method should handle assigning the partitions automatically to the consumer based on group id parameters of readerKafkaProperties.
            kafkaConsumer = new KafkaConsumer<>(readerKafkaProperties, new ByteArrayDeserializer(), new ByteArrayDeserializer());
            kafkaConsumer.subscribe(Collections.singletonList(topic));
        }

        Set<TopicPartition> assignment = kafkaConsumer.assignment();
        // Seek the consumer to topic partition offset defined by the latest record that is committed to HDFS.
        for (TopicPartition topicPartition : assignment) {
            if (hdfsStartOffsets.containsKey(topicPartition)) {
                long position = kafkaConsumer.position(topicPartition);
                if (position < hdfsStartOffsets.get(topicPartition)) {
                    kafkaConsumer.seek(topicPartition, hdfsStartOffsets.get(topicPartition));
                }
            }
        }

        return new KafkaReader(kafkaConsumer, callbackFunction);
    }

    // Part or Runnable implementation, called when the thread is started.
    @Override
    public void run() {
        boolean useMockKafkaConsumer = Boolean.parseBoolean(readerKafkaProperties.getProperty("useMockKafkaConsumer", "false"));
        try (
                KafkaReader kafkaReader = createKafkaReader(
                        readerKafkaProperties,
                        queueTopic,
                        callbackFunction,
                        useMockKafkaConsumer
                )
        ) {
            while (run) {
                kafkaReader.read();
            }
        }
    }

    // remove?
    public void stop() {
        run = false;
    }
}