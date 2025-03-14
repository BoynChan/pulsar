/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.transaction.buffer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

import com.google.common.collect.Sets;
import io.netty.util.HashedWheelTimer;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.Cleanup;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.pulsar.broker.intercept.MockBrokerInterceptor;
import org.apache.pulsar.broker.service.BrokerService;
import org.apache.pulsar.broker.service.Subscription;
import org.apache.pulsar.broker.service.Topic;
import org.apache.pulsar.broker.transaction.buffer.impl.TransactionBufferClientImpl;
import org.apache.pulsar.broker.transaction.buffer.impl.TransactionBufferHandlerImpl;
import org.apache.pulsar.broker.transaction.coordinator.TransactionMetaStoreTestBase;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.transaction.TransactionBufferClient;
import org.apache.pulsar.client.api.transaction.TransactionBufferClientException;
import org.apache.pulsar.client.api.transaction.TxnID;
import org.apache.pulsar.client.impl.PulsarClientImpl;
import org.apache.pulsar.common.api.proto.TxnAction;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.apache.pulsar.common.util.collections.ConcurrentOpenHashMap;
import org.awaitility.Awaitility;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

@Test(groups = "broker")
public class TransactionBufferClientTest extends TransactionMetaStoreTestBase {

    private static final Logger log = LoggerFactory.getLogger(TransactionBufferClientTest.class);
    private TransactionBufferClient tbClient;
    TopicName partitionedTopicName = TopicName.get("persistent", "public", "test", "tb-client");
    int partitions = 10;
    BrokerService[] brokerServices;
    private final static String namespace = "public/test";

    @Override
    protected void afterSetup() throws Exception {
        pulsarAdmins[0].clusters().createCluster("my-cluster", new ClusterData(pulsarServices[0].getWebServiceAddress()));
        pulsarAdmins[0].tenants().createTenant("public", new TenantInfo(Sets.newHashSet(), Sets.newHashSet("my-cluster")));
        pulsarAdmins[0].namespaces().createNamespace(namespace, 10);
        pulsarAdmins[0].topics().createPartitionedTopic(partitionedTopicName.getPartitionedTopicName(), partitions);
        pulsarClient.newConsumer()
                .topic(partitionedTopicName.getPartitionedTopicName())
                .subscriptionName("test").subscribe();
        tbClient = TransactionBufferClientImpl.create(
                ((PulsarClientImpl) pulsarClient),
                new HashedWheelTimer(new DefaultThreadFactory("transaction-buffer")));
    }

    @Override
    protected void cleanup() throws Exception {
        if (tbClient != null) {
            tbClient.close();
        }
        if (brokerServices != null) {
            for (BrokerService bs : brokerServices) {
                bs.close();
            }
            brokerServices = null;
        }
        super.cleanup();
    }

    @Override
    protected void afterPulsarStart() throws Exception {
        brokerServices = new BrokerService[pulsarServices.length];
        AtomicLong atomicLong = new AtomicLong(0);
        for (int i = 0; i < pulsarServices.length; i++) {
            Subscription mockSubscription = mock(Subscription.class);
            Mockito.when(mockSubscription.endTxn(Mockito.anyLong(),
                    Mockito.anyLong(), Mockito.anyInt(), Mockito.anyLong()))
                    .thenReturn(CompletableFuture.completedFuture(null));

            Topic mockTopic = mock(Topic.class);
            Mockito.when(mockTopic.endTxn(any(), Mockito.anyInt(), anyLong()))
                    .thenReturn(CompletableFuture.completedFuture(null));
            Mockito.when(mockTopic.getSubscription(any())).thenReturn(mockSubscription);

            ConcurrentOpenHashMap<String, CompletableFuture<Optional<Topic>>> topicMap =
                    mock(ConcurrentOpenHashMap.class);
            Mockito.when(topicMap.get(Mockito.anyString())).thenReturn(
                    CompletableFuture.completedFuture(Optional.of(mockTopic)));

            BrokerService brokerService = Mockito.spy(new BrokerService(pulsarServices[i]));
            doReturn(new MockBrokerInterceptor()).when(brokerService).getInterceptor();
            doReturn(atomicLong.getAndIncrement() + "").when(brokerService).generateUniqueProducerName();
            brokerServices[i] = brokerService;
            Mockito.when(brokerService.getTopics()).thenReturn(topicMap);
            Mockito.when(pulsarServices[i].getBrokerService()).thenReturn(brokerService);
        }
    }

    @Test
    public void testCommitOnTopic() throws ExecutionException, InterruptedException {
        List<CompletableFuture<TxnID>> futures = new ArrayList<>();
        for (int i = 0; i < partitions; i++) {
            String topic = partitionedTopicName.getPartition(i).toString();
            futures.add(tbClient.commitTxnOnTopic(topic, 1L, i, Long.MIN_VALUE));
        }
        for (int i = 0; i < futures.size(); i++) {
            assertEquals(futures.get(i).get().getMostSigBits(), 1L);
            assertEquals(futures.get(i).get().getLeastSigBits(), i);
        }
    }

    @Test
    public void testAbortOnTopic() throws ExecutionException, InterruptedException {
        List<CompletableFuture<TxnID>> futures = new ArrayList<>();
        for (int i = 0; i < partitions; i++) {
            String topic = partitionedTopicName.getPartition(i).toString();
            futures.add(tbClient.abortTxnOnTopic(topic, 1L, i, Long.MIN_VALUE));
        }
        for (int i = 0; i < futures.size(); i++) {
            assertEquals(futures.get(i).get().getMostSigBits(), 1L);
            assertEquals(futures.get(i).get().getLeastSigBits(), i);
        }
    }

    @Test
    public void testCommitOnSubscription() throws ExecutionException, InterruptedException {
        List<CompletableFuture<TxnID>> futures = new ArrayList<>();
        for (int i = 0; i < partitions; i++) {
            String topic = partitionedTopicName.getPartition(i).toString();
            futures.add(tbClient.commitTxnOnSubscription(topic, "test", 1L, i, -1L));
        }
        for (int i = 0; i < futures.size(); i++) {
            assertEquals(futures.get(i).get().getMostSigBits(), 1L);
            assertEquals(futures.get(i).get().getLeastSigBits(), i);
        }
    }

    @Test
    public void testAbortOnSubscription() throws ExecutionException, InterruptedException {
        List<CompletableFuture<TxnID>> futures = new ArrayList<>();
        for (int i = 0; i < partitions; i++) {
            String topic = partitionedTopicName.getPartition(i).toString();
            futures.add(tbClient.abortTxnOnSubscription(topic, "test", 1L, i, -1L));
        }
        for (int i = 0; i < futures.size(); i++) {
            assertEquals(futures.get(i).get().getMostSigBits(), 1L);
            assertEquals(futures.get(i).get().getLeastSigBits(), i);
        }
    }

    @Test
    public void testTransactionBufferOpFail() throws InterruptedException, ExecutionException {
        ConcurrentOpenHashMap<String, CompletableFuture<Optional<Topic>>>[] originalMaps =
                new ConcurrentOpenHashMap[brokerServices.length];
        ConcurrentOpenHashMap<String, CompletableFuture<Optional<Topic>>> topicMap = new ConcurrentOpenHashMap<>();
        for (int i = 0; i < brokerServices.length; i++) {
            originalMaps[i] = brokerServices[i].getTopics();
            when(brokerServices[i].getTopics()).thenReturn(topicMap);
        }

        try {
            tbClient.abortTxnOnSubscription(
                    partitionedTopicName.getPartition(0).toString(), "test", 1L, 1, -1L).get();
            fail();
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof PulsarClientException.LookupException);
        }

        try {
            tbClient.abortTxnOnTopic(
                    partitionedTopicName.getPartition(0).toString(), 1L, 1, -1L).get();
            fail();
        } catch (ExecutionException e) {
            assertTrue(e.getCause() instanceof PulsarClientException.LookupException);
        }

        for (int i = 0; i < brokerServices.length; i++) {
            when(brokerServices[i].getTopics()).thenReturn(originalMaps[i]);
        }

        tbClient.abortTxnOnSubscription(
                partitionedTopicName.getPartition(0).toString(), "test", 1L, 1, -1L).get();

        tbClient.abortTxnOnTopic(
                partitionedTopicName.getPartition(0).toString(), 1L, 1, -1L).get();
    }

    @Test
    public void testTransactionBufferClientTimeout() throws Exception {
        PulsarClientImpl mockClient = mock(PulsarClientImpl.class);
        when(mockClient.getConnection(anyString())).thenReturn(new CompletableFuture<>());
        @Cleanup("stop")
        HashedWheelTimer hashedWheelTimer = new HashedWheelTimer();
        TransactionBufferHandlerImpl transactionBufferHandler =
                new TransactionBufferHandlerImpl(mockClient, hashedWheelTimer);
        CompletableFuture<TxnID> completableFuture =
                transactionBufferHandler.endTxnOnTopic("test", 1, 1, TxnAction.ABORT, 1);

        Field field = TransactionBufferHandlerImpl.class.getDeclaredField("pendingRequests");
        field.setAccessible(true);
        ConcurrentSkipListMap<Long, Object> pendingRequests =
                (ConcurrentSkipListMap<Long, Object>) field.get(transactionBufferHandler);

        assertEquals(pendingRequests.size(), 1);

        Awaitility.await().atLeast(2, TimeUnit.SECONDS).until(() -> {
            if (pendingRequests.size() == 0) {
                return true;
            }
            return false;
        });

        try {
            completableFuture.get();
            fail();
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof TransactionBufferClientException.RequestTimeoutException);
        }
    }

    @Test
    public void testTransactionBufferLookUp() throws ExecutionException, InterruptedException {
        String topic = "persistent://" + namespace + "/testTransactionBufferLookUp";
        tbClient.abortTxnOnSubscription(topic + "_abort_sub", "test", 1L, 1L, -1L).get();
        tbClient.commitTxnOnSubscription(topic + "_commit_sub", "test", 1L, 1L, -1L).get();
        tbClient.abortTxnOnTopic(topic + "_abort_topic", 1L, 1L, -1L).get();
        tbClient.commitTxnOnTopic(topic + "_commit_topic", 1L, 1L, -1L).get();
    }
}
