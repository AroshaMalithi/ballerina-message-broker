/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package io.ballerina.messaging.broker.core.store;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.EventTranslatorTwoArg;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import io.ballerina.messaging.broker.core.BrokerException;
import io.ballerina.messaging.broker.core.Message;
import io.ballerina.messaging.broker.core.queue.QueueBuffer;
import io.ballerina.messaging.broker.core.store.dao.MessageDao;
import io.ballerina.messaging.broker.core.store.disruptor.SleepingBlockingWaitStrategy;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import javax.annotation.concurrent.ThreadSafe;
import javax.transaction.xa.Xid;

/**
 * Message store class that is used by all the durable queues to persist messages
 * <p>
 * Note: This class is thread safe
 */
@ThreadSafe
public class SharedMessageStore {

    private final Map<Long, Message> pendingMessages;

    private final Disruptor<DbOperation> disruptor;

    private static final EventTranslatorOneArg<DbOperation, Message> INSERT_MESSAGE =
            (event, sequence, message) -> event.insertMessage(message);

    private static final EventTranslatorTwoArg<DbOperation, String, Long> DETACH_FROM_QUEUE =
            (event, sequence, queueName, messageId) -> event.detachFromQueue(queueName, messageId);

    private static final EventTranslatorOneArg<DbOperation, Long> DELETE_MESSAGE =
            (event, sequence, messageId) -> event.deleteMessage(messageId);

    private static final EventTranslatorTwoArg<DbOperation, QueueBuffer, Message> READ_MESSAGE_DATA =
            (event, sequence, queueBuffer, message) -> event.readMessageData(queueBuffer, message);

    private final MessageDao messageDao;

    private final Map<Xid, TransactionData> transactionMap;

    @SuppressWarnings("unchecked") SharedMessageStore(MessageDao messageDao, int bufferSize, int maxDbBatchSize) {

        pendingMessages = new ConcurrentHashMap<>();
        transactionMap = new ConcurrentHashMap<>();
        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("DisruptorMessageStoreThread-%d").build();

        disruptor = new Disruptor<>(DbOperation.getFactory(),
                                    bufferSize, namedThreadFactory, ProducerType.MULTI, new
                                            SleepingBlockingWaitStrategy());

        disruptor.setDefaultExceptionHandler(new LogExceptionHandler());

        disruptor.handleEventsWith(new DbEventMatcher(bufferSize))
                 .then(new DbAccessHandler(messageDao, maxDbBatchSize))
                 .then(new FinalEventHandler());
        disruptor.start();
        this.messageDao = messageDao;
    }

    public void add(Message message) {
        pendingMessages.put(message.getInternalId(), message);
    }

    public void add(Xid xid, Message message) throws BrokerException {
        TransactionData transactionData = getTransactionData(xid);
        transactionData.addEnqueueMessage(message);
    }

    public void attach(String queueName, long messageInternalId) throws BrokerException {
        Message message = pendingMessages.get(messageInternalId);
        if (message == null) {
            throw new BrokerException("Unknown message id " + messageInternalId
                                              + " cannot attach to queue " + queueName);
        }
        message.addOwnedQueue(queueName);
    }

    public void attach(Xid xid, String queueName, long messageInternalId) throws BrokerException {
        TransactionData transactionData = getTransactionData(xid);
        transactionData.attach(queueName, messageInternalId);
    }

    private TransactionData getTransactionData(Xid xid) throws BrokerException {
        TransactionData transactionData = transactionMap.get(xid);
        if (Objects.isNull(transactionData)) {
            throw new BrokerException("Unknown Xid " + xid + ". Create a branch with Xid before attaching to a queue");
        }
        return transactionData;
    }

    private void delete(long messageId) {
        disruptor.publishEvent(DELETE_MESSAGE, messageId);
    }

    public synchronized void detach(String queueName, final Message message) {
        message.removeAttachedQueue(queueName);
        if (!message.hasAttachedQueues()) {
            delete(message.getInternalId());
        } else {
            disruptor.publishEvent(DETACH_FROM_QUEUE, queueName, message.getInternalId());
        }
    }

    public synchronized void detach(Xid xid, String queueName, Message message) throws BrokerException {
        TransactionData transactionData = getTransactionData(xid);
        message.removeAttachedQueue(queueName);
        if (message.hasAttachedQueues()) {
            transactionData.detach(queueName, message.getInternalId());
        } else {
            transactionData.addDeletableMessage(message.getInternalId());
        }
    }

    public void readData(QueueBuffer queueBuffer, Message message) {
        disruptor.publishEvent(READ_MESSAGE_DATA, queueBuffer, message);
    }

    public void flush(long internalMessageId) {
        Message message = pendingMessages.remove(internalMessageId);
        if (message != null) {

            if (message.hasAttachedQueues()) {
                disruptor.publishEvent(INSERT_MESSAGE, message);
            } else {
                message.release();
            }
        }
    }

    public void flush(Xid xid) throws BrokerException {
        messageDao.persist(getTransactionData(xid));
        clear(xid);
    }

    public void branch(Xid xid) {
        transactionMap.putIfAbsent(xid, new TransactionData());
    }

    public Collection<Message> readStoredMessages(String queueName) throws BrokerException {
        return messageDao.readAll(queueName);
    }

    /**
     * Method to clear all pending messages.
     */
    public void clearPendingMessages() {
        pendingMessages.clear();
    }

    public void clear(Xid xid) {
        TransactionData transactionData = transactionMap.remove(xid);
        transactionData.releaseEnqueueMessages();
        transactionData.clear();
    }
}
