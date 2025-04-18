/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.state.api.input;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.io.DefaultInputSplitAssigner;
import org.apache.flink.api.common.io.RichInputFormat;
import org.apache.flink.api.common.io.statistics.BaseStatistics;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.CloseableRegistry;
import org.apache.flink.core.io.InputSplitAssigner;
import org.apache.flink.runtime.checkpoint.OperatorState;
import org.apache.flink.runtime.checkpoint.StateAssignmentOperation;
import org.apache.flink.runtime.state.AbstractKeyedStateBackend;
import org.apache.flink.runtime.state.DefaultKeyedStateStore;
import org.apache.flink.runtime.state.KeyGroupRange;
import org.apache.flink.runtime.state.KeyedStateHandle;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.state.api.functions.KeyedStateReaderFunction;
import org.apache.flink.state.api.input.operator.StateReaderOperator;
import org.apache.flink.state.api.input.splits.KeyGroupRangeInputSplit;
import org.apache.flink.state.api.runtime.SavepointRuntimeContext;
import org.apache.flink.streaming.api.operators.InternalTimeServiceManager;
import org.apache.flink.streaming.api.operators.StreamOperatorStateContext;
import org.apache.flink.util.CloseableIterator;
import org.apache.flink.util.CollectionUtil;
import org.apache.flink.util.IOUtils;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.SerializedValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;

/**
 * Input format for reading partitioned state.
 *
 * @param <K> The type of the key.
 * @param <OUT> The type of the output of the {@link KeyedStateReaderFunction}.
 */
@Internal
public class KeyedStateInputFormat<K, N, OUT>
        extends RichInputFormat<OUT, KeyGroupRangeInputSplit> {

    private static final long serialVersionUID = 8230460226049597182L;

    private static final Logger LOG = LoggerFactory.getLogger(KeyedStateInputFormat.class);

    private final OperatorState operatorState;

    @Nullable private final StateBackend stateBackend;

    private final Configuration configuration;

    private final StateReaderOperator<?, K, N, OUT> operator;

    private final SerializedValue<ExecutionConfig> serializedExecutionConfig;

    private transient CloseableRegistry registry;

    private transient BufferingCollector<OUT> out;

    private transient CloseableIterator<Tuple2<K, N>> keysAndNamespaces;

    /**
     * Creates an input format for reading partitioned state from an operator in a savepoint.
     *
     * @param operatorState The state to be queried.
     * @param stateBackend The state backed used to snapshot the operator.
     * @param configuration The underlying Flink configuration used to configure the state backend.
     */
    public KeyedStateInputFormat(
            OperatorState operatorState,
            @Nullable StateBackend stateBackend,
            Configuration configuration,
            StateReaderOperator<?, K, N, OUT> operator,
            ExecutionConfig executionConfig)
            throws IOException {
        Preconditions.checkNotNull(operatorState, "The operator state cannot be null");
        Preconditions.checkNotNull(configuration, "The configuration cannot be null");
        Preconditions.checkNotNull(operator, "The operator cannot be null");
        Preconditions.checkNotNull(executionConfig, "The executionConfig cannot be null");

        this.operatorState = operatorState;
        this.stateBackend = stateBackend;
        // Eagerly deep copy the configuration object
        // otherwise there will be undefined behavior
        // when executing pipelines with multiple input formats
        this.configuration = new Configuration(configuration);
        this.operator = operator;
        this.serializedExecutionConfig = new SerializedValue<>(executionConfig);
    }

    @Override
    public void configure(Configuration parameters) {}

    @Override
    public InputSplitAssigner getInputSplitAssigner(KeyGroupRangeInputSplit[] inputSplits) {
        return new DefaultInputSplitAssigner(inputSplits);
    }

    @Override
    public BaseStatistics getStatistics(BaseStatistics cachedStatistics) {
        return cachedStatistics;
    }

    @Override
    public KeyGroupRangeInputSplit[] createInputSplits(int minNumSplits) throws IOException {
        final int maxParallelism = operatorState.getMaxParallelism();

        final List<KeyGroupRange> keyGroups = sortedKeyGroupRanges(minNumSplits, maxParallelism);

        return CollectionUtil.mapWithIndex(
                        keyGroups,
                        (keyGroupRange, index) ->
                                createKeyGroupRangeInputSplit(
                                        operatorState, maxParallelism, keyGroupRange, index))
                .toArray(KeyGroupRangeInputSplit[]::new);
    }

    @Override
    public void openInputFormat() {
        out = new BufferingCollector<>();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void open(KeyGroupRangeInputSplit split) throws IOException {
        registry = new CloseableRegistry();

        RuntimeContext runtimeContext = getRuntimeContext();
        ExecutionConfig executionConfig;
        try {
            executionConfig =
                    serializedExecutionConfig.deserializeValue(
                            runtimeContext.getUserCodeClassLoader());
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Could not deserialize ExecutionConfig.", e);
        }
        final StreamOperatorStateContext context =
                new StreamOperatorContextBuilder(
                                runtimeContext,
                                configuration,
                                operatorState,
                                split,
                                registry,
                                stateBackend,
                                executionConfig)
                        .withMaxParallelism(split.getNumKeyGroups())
                        .withKey(operator, runtimeContext.createSerializer(operator.getKeyType()))
                        .build(LOG);

        AbstractKeyedStateBackend<K> keyedStateBackend =
                (AbstractKeyedStateBackend<K>) context.keyedStateBackend();

        final DefaultKeyedStateStore keyedStateStore =
                new DefaultKeyedStateStore(keyedStateBackend, runtimeContext::createSerializer);
        SavepointRuntimeContext ctx = new SavepointRuntimeContext(runtimeContext, keyedStateStore);

        InternalTimeServiceManager<K> timeServiceManager =
                (InternalTimeServiceManager<K>) context.internalTimerServiceManager();
        try {
            operator.setup(
                    runtimeContext::createSerializer, keyedStateBackend, timeServiceManager, ctx);
            operator.open();
            keysAndNamespaces = operator.getKeysAndNamespaces(ctx);
        } catch (Exception e) {
            throw new IOException("Failed to restore timer state", e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            IOUtils.closeQuietly(keysAndNamespaces);
            IOUtils.closeQuietly(operator);
            IOUtils.closeQuietly(registry);
        } catch (Exception e) {
            throw new IOException("Failed to close state backend", e);
        }
    }

    @Override
    public boolean reachedEnd() {
        return !out.hasNext() && !keysAndNamespaces.hasNext();
    }

    @Override
    public OUT nextRecord(OUT reuse) throws IOException {
        if (out.hasNext()) {
            return out.next();
        }

        final Tuple2<K, N> keyAndNamespace = keysAndNamespaces.next();
        operator.setCurrentKey(keyAndNamespace.f0);

        try {
            operator.processElement(keyAndNamespace.f0, keyAndNamespace.f1, out);
        } catch (Exception e) {
            throw new IOException(
                    "User defined function KeyedStateReaderFunction#readKey threw an exception", e);
        }

        return out.next();
    }

    private static KeyGroupRangeInputSplit createKeyGroupRangeInputSplit(
            OperatorState operatorState,
            int maxParallelism,
            KeyGroupRange keyGroupRange,
            Integer index) {

        final List<KeyedStateHandle> managedKeyedState =
                StateAssignmentOperation.getManagedKeyedStateHandles(operatorState, keyGroupRange);
        final List<KeyedStateHandle> rawKeyedState =
                StateAssignmentOperation.getRawKeyedStateHandles(operatorState, keyGroupRange);

        return new KeyGroupRangeInputSplit(managedKeyedState, rawKeyedState, maxParallelism, index);
    }

    @Nonnull
    private static List<KeyGroupRange> sortedKeyGroupRanges(int minNumSplits, int maxParallelism) {
        List<KeyGroupRange> keyGroups =
                StateAssignmentOperation.createKeyGroupPartitions(
                        maxParallelism, Math.min(minNumSplits, maxParallelism));

        keyGroups.sort(Comparator.comparing(KeyGroupRange::getStartKeyGroup));
        return keyGroups;
    }
}
