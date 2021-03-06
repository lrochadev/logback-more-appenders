/**
 * Copyright (c) 2018 sndyuk <sanada@sndyuk.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package ch.qos.logback.more.appenders;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder;
import com.amazonaws.services.kinesis.model.CreateStreamRequest;
import com.amazonaws.services.kinesis.model.DescribeStreamRequest;
import com.amazonaws.services.kinesis.model.DescribeStreamResult;
import com.amazonaws.services.kinesis.model.ResourceNotFoundException;
import ch.qos.logback.core.encoder.EchoEncoder;
import ch.qos.logback.core.encoder.Encoder;

public abstract class KinesisStreamAppenderBase<E> extends AwsAppender<E> {

    protected AmazonKinesis kinesis;
    protected String streamName;
    protected int shardCount;
    protected boolean createStreamDestination;
    protected Encoder<E> encoder = new EchoEncoder<E>();
    protected volatile boolean active;

    @Override
    public void start() {
        if (streamName == null || streamName.length() == 0) {
            throw new IllegalArgumentException("streamName must be defined.");
        }
        super.start();
    }

    @Override
    public void stop() {
        try {
            super.stop();
        } finally {
            try {
                kinesis.shutdown();
            } catch (Exception e) {
                // pass
            }
        }
    }

    protected void ensureKinesisStream() {
        if (this.kinesis == null) {
            this.kinesis = AmazonKinesisClientBuilder.standard()
              .withCredentials(new AWSStaticCredentialsProvider(credentials))
              .withRegion(config.getRegion()).build();
        }
        try {
            kinesis.describeStream(streamName);
            active = true;
            return;
        } catch (ResourceNotFoundException e) {
            // pass
        }
        // Watch the stream becomes ACTIVE.
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                CreateStreamRequest createStreamRequest = new CreateStreamRequest();
                createStreamRequest.setStreamName(streamName);
                createStreamRequest.setShardCount(shardCount);
                kinesis.createStream(createStreamRequest);

                DescribeStreamRequest describeStreamRequest = new DescribeStreamRequest();
                describeStreamRequest.setStreamName(streamName);

                long startTime = System.currentTimeMillis();
                long endTime = startTime + (120 * 1000);
                while (true) {
                    try {
                        Thread.sleep(5 * 1000);
                    } catch (Exception e) {
                        // pass
                    }
                    try {
                        DescribeStreamResult describeStreamResponse =
                                kinesis.describeStream(describeStreamRequest);
                        String streamStatus =
                                describeStreamResponse.getStreamDescription().getStreamStatus();
                        if (streamStatus.equals("ACTIVE")) {
                            active = true;
                            return;
                        }
                    } catch (ResourceNotFoundException e) {
                        // pass
                    }
                    if (System.currentTimeMillis() >= endTime) {
                        addError("Stream " + streamName + " never went active.");
                        return;
                    }
                }
            }
        });
        th.setDaemon(true);
        th.start();
    }

    public interface PartitionKey<E> {
        String get(E event);
    }
}
