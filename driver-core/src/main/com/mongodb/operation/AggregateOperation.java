/*
 * Copyright (c) 2008-2014 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.operation;

import com.mongodb.ExplainVerbosity;
import com.mongodb.Function;
import com.mongodb.MongoNamespace;
import com.mongodb.ReadConcern;
import com.mongodb.async.AsyncBatchCursor;
import com.mongodb.async.SingleResultCallback;
import com.mongodb.binding.AsyncConnectionSource;
import com.mongodb.binding.AsyncReadBinding;
import com.mongodb.binding.ConnectionSource;
import com.mongodb.binding.ReadBinding;
import com.mongodb.connection.AsyncConnection;
import com.mongodb.connection.Connection;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.connection.QueryResult;
import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.codecs.Decoder;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.mongodb.assertions.Assertions.notNull;
import static com.mongodb.internal.async.ErrorHandlingResultCallback.errorHandlingCallback;
import static com.mongodb.operation.CommandOperationHelper.executeWrappedCommandProtocol;
import static com.mongodb.operation.CommandOperationHelper.executeWrappedCommandProtocolAsync;
import static com.mongodb.operation.OperationHelper.AsyncCallableWithConnectionAndSource;
import static com.mongodb.operation.OperationHelper.CallableWithConnectionAndSource;
import static com.mongodb.operation.OperationHelper.checkValidReadConcern;
import static com.mongodb.operation.OperationHelper.cursorDocumentToQueryResult;
import static com.mongodb.operation.OperationHelper.releasingCallback;
import static com.mongodb.operation.OperationHelper.serverIsAtLeastVersionTwoDotSix;
import static com.mongodb.operation.OperationHelper.withConnection;

/**
 * An operation that executes an aggregation query.
 *
 * @param <T> the operations result type.
 * @mongodb.driver.manual aggregation/ Aggregation
 * @mongodb.server.release 2.2
 * @since 3.0
 */
public class AggregateOperation<T> implements AsyncReadOperation<AsyncBatchCursor<T>>, ReadOperation<BatchCursor<T>> {
    private static final String RESULT = "result";
    private static final String FIRST_BATCH = "firstBatch";

    private final MongoNamespace namespace;
    private final List<BsonDocument> pipeline;
    private final Decoder<T> decoder;
    private Boolean allowDiskUse;
    private Integer batchSize;
    private long maxTimeMS;
    private Boolean useCursor;
    private ReadConcern readConcern = ReadConcern.DEFAULT;

    /**
     * Construct a new instance.
     *
     * @param namespace the database and collection namespace for the operation.
     * @param pipeline the aggregation pipeline.
     * @param decoder the decoder for the result documents.
     */
    public AggregateOperation(final MongoNamespace namespace, final List<BsonDocument> pipeline, final Decoder<T> decoder) {
        this.namespace = notNull("namespace", namespace);
        this.pipeline = notNull("pipeline", pipeline);
        this.decoder = notNull("decoder", decoder);
    }

    /**
     * Gets the aggregation pipeline.
     *
     * @return the pipeline
     * @mongodb.driver.manual core/aggregation-introduction/#aggregation-pipelines Aggregation Pipeline
     */
    public List<BsonDocument> getPipeline() {
        return pipeline;
    }

    /**
     * Whether writing to temporary files is enabled. A null value indicates that it's unspecified.
     *
     * @return true if writing to temporary files is enabled
     * @mongodb.driver.manual reference/command/aggregate/ Aggregation
     * @mongodb.server.release 2.6
     */
    public Boolean getAllowDiskUse() {
        return allowDiskUse;
    }

    /**
     * Enables writing to temporary files. A null value indicates that it's unspecified.
     *
     * @param allowDiskUse true if writing to temporary files is enabled
     * @return this
     * @mongodb.driver.manual reference/command/aggregate/ Aggregation
     * @mongodb.server.release 2.6
     */
    public AggregateOperation<T> allowDiskUse(final Boolean allowDiskUse) {
        this.allowDiskUse = allowDiskUse;
        return this;
    }

    /**
     * Gets the number of documents to return per batch.  Default to 0, which indicates that the server chooses an appropriate batch size.
     *
     * @return the batch size, which may be null
     * @mongodb.driver.manual reference/method/cursor.batchSize/#cursor.batchSize Batch Size
     */
    public Integer getBatchSize() {
        return batchSize;
    }

    /**
     * Sets the number of documents to return per batch.
     *
     * @param batchSize the batch size
     * @return this
     * @mongodb.driver.manual reference/method/cursor.batchSize/#cursor.batchSize Batch Size
     */
    public AggregateOperation<T> batchSize(final Integer batchSize) {
        this.batchSize = batchSize;
        return this;
    }

    /**
     * Gets the maximum execution time on the server for this operation.  The default is 0, which places no limit on the execution time.
     *
     * @param timeUnit the time unit to return the result in
     * @return the maximum execution time in the given time unit
     * @mongodb.driver.manual reference/method/cursor.maxTimeMS/#cursor.maxTimeMS Max Time
     */
    public long getMaxTime(final TimeUnit timeUnit) {
        notNull("timeUnit", timeUnit);
        return timeUnit.convert(maxTimeMS, TimeUnit.MILLISECONDS);
    }

    /**
     * Sets the maximum execution time on the server for this operation.
     *
     * @param maxTime  the max time
     * @param timeUnit the time unit, which may not be null
     * @return this
     * @mongodb.driver.manual reference/method/cursor.maxTimeMS/#cursor.maxTimeMS Max Time
     */
    public AggregateOperation<T> maxTime(final long maxTime, final TimeUnit timeUnit) {
        notNull("timeUnit", timeUnit);
        this.maxTimeMS = TimeUnit.MILLISECONDS.convert(maxTime, timeUnit);
        return this;
    }

    /**
     * Gets whether the server should use a cursor to return results.  The default value is null, in which case a cursor will be used if the
     * server supports it.
     *
     * @return whether the server should use a cursor to return results
     * @mongodb.driver.manual reference/command/aggregate/ Aggregation
     * @mongodb.server.release 2.6
     */
    public Boolean getUseCursor() {
        return useCursor;
    }

    /**
     * Sets whether the server should use a cursor to return results.
     *
     * @param useCursor whether the server should use a cursor to return results
     * @return this
     * @mongodb.driver.manual reference/command/aggregate/ Aggregation
     * @mongodb.server.release 2.6
     */
    public AggregateOperation<T> useCursor(final Boolean useCursor) {
        this.useCursor = useCursor;
        return this;
    }

    /**
     * Gets the read concern
     *
     * @return the read concern
     * @since 3.2
     */
    public ReadConcern getReadConcern() {
        return readConcern;
    }

    /**
     * Sets the read concern
     * @param readConcern the read concern
     * @return this
     * @since 3.2
     */
    public AggregateOperation<T> readConcern(final ReadConcern readConcern) {
        this.readConcern = notNull("readConcern", readConcern);
        return this;
    }

    @Override
    public BatchCursor<T> execute(final ReadBinding binding) {
        return withConnection(binding, new CallableWithConnectionAndSource<BatchCursor<T>>() {
            @Override
            public BatchCursor<T> call(final ConnectionSource source, final Connection connection) {
                checkValidReadConcern(connection, readConcern);
                return executeWrappedCommandProtocol(binding, namespace.getDatabaseName(), asCommandDocument(connection.getDescription()),
                        CommandResultDocumentCodec.create(decoder, getFieldNameWithResults(connection.getDescription())),
                        connection, transformer(source, connection));
            }
        });
    }

    @Override
    public void executeAsync(final AsyncReadBinding binding, final SingleResultCallback<AsyncBatchCursor<T>> callback) {
        withConnection(binding, new AsyncCallableWithConnectionAndSource() {
            @Override
            public void call(final AsyncConnectionSource source, final AsyncConnection connection, final Throwable t) {
                if (t != null) {
                    errorHandlingCallback(callback).onResult(null, t);
                } else {
                    final SingleResultCallback<AsyncBatchCursor<T>> wrappedCallback = releasingCallback(errorHandlingCallback(callback),
                            source, connection);
                    checkValidReadConcern(source, connection, readConcern, new AsyncCallableWithConnectionAndSource() {
                        @Override
                        public void call(final AsyncConnectionSource source, final AsyncConnection connection, final Throwable t) {
                            if (t != null) {
                                wrappedCallback.onResult(null, t);
                            } else {
                                executeWrappedCommandProtocolAsync(binding, namespace.getDatabaseName(),
                                        asCommandDocument(connection.getDescription()),
                                        CommandResultDocumentCodec.create(decoder, getFieldNameWithResults(connection.getDescription())),
                                        connection, asyncTransformer(source, connection), wrappedCallback);
                            }
                        }
                    });
                }
            }
        });
    }

    /**
     * Gets an operation whose execution explains this operation.
     *
     * @param explainVerbosity the explain verbosity
     * @return a read operation that when executed will explain this operation
     */
    public ReadOperation<BsonDocument> asExplainableOperation(final ExplainVerbosity explainVerbosity) {
        return new AggregateExplainOperation(namespace, pipeline)
               .allowDiskUse(allowDiskUse)
               .maxTime(maxTimeMS, TimeUnit.MILLISECONDS);
    }

    /**
     * Gets an operation whose execution explains this operation.
     *
     * @param explainVerbosity the explain verbosity
     * @return a read operation that when executed will explain this operation
     */
    public AsyncReadOperation<BsonDocument> asExplainableOperationAsync(final ExplainVerbosity explainVerbosity) {
        return new AggregateExplainOperation(namespace, pipeline)
               .allowDiskUse(allowDiskUse)
               .maxTime(maxTimeMS, TimeUnit.MILLISECONDS);
    }

    private boolean isInline(final ConnectionDescription description) {
        return (useCursor != null && !useCursor) || !serverIsAtLeastVersionTwoDotSix(description);
    }

    private BsonDocument asCommandDocument(final ConnectionDescription description) {
        BsonDocument commandDocument = new BsonDocument("aggregate", new BsonString(namespace.getCollectionName()));
        commandDocument.put("pipeline", new BsonArray(pipeline));
        if (maxTimeMS > 0) {
            commandDocument.put("maxTimeMS", new BsonInt64(maxTimeMS));
        }
        if ((useCursor == null || useCursor) && serverIsAtLeastVersionTwoDotSix(description)) {
            BsonDocument cursor = new BsonDocument();
            if (batchSize != null) {
                cursor.put("batchSize", new BsonInt32(batchSize));
            }
            commandDocument.put("cursor", cursor);
        }
        if (allowDiskUse != null) {
            commandDocument.put("allowDiskUse", BsonBoolean.valueOf(allowDiskUse));
        }
        if (!readConcern.isServerDefault()) {
            commandDocument.put("readConcern", readConcern.asDocument());
        }

        return commandDocument;
    }

    String getFieldNameWithResults(final ConnectionDescription description) {
        return ((useCursor == null || useCursor) && serverIsAtLeastVersionTwoDotSix(description)) ? FIRST_BATCH : RESULT;
    }

    @SuppressWarnings("unchecked")
    private QueryResult<T> createQueryResult(final BsonDocument result, final ConnectionDescription description) {
        if (isInline(description)) {
            return new QueryResult<T>(namespace, BsonDocumentWrapperHelper.<T>toList(result, RESULT), 0L,
                                      description.getServerAddress());
        } else {
            return cursorDocumentToQueryResult(result.getDocument("cursor"), description.getServerAddress());
        }
    }

    private Function<BsonDocument, BatchCursor<T>> transformer(final ConnectionSource source, final Connection connection) {
        return new Function<BsonDocument, BatchCursor<T>>() {
            @Override
            public BatchCursor<T> apply(final BsonDocument result) {
                QueryResult<T> queryResult = createQueryResult(result, connection.getDescription());
                return new QueryBatchCursor<T>(queryResult, 0, batchSize != null ? batchSize : 0, decoder, source);
            }
        };
    }

    private Function<BsonDocument, AsyncBatchCursor<T>> asyncTransformer(final AsyncConnectionSource source,
                                                                         final AsyncConnection connection) {
        return new Function<BsonDocument, AsyncBatchCursor<T>>() {
            @Override
            public AsyncBatchCursor<T> apply(final BsonDocument result) {
                QueryResult<T> queryResult = createQueryResult(result, connection.getDescription());
                return new AsyncQueryBatchCursor<T>(queryResult, 0, batchSize != null ? batchSize : 0, decoder, source, connection);
            }
        };
    }
}
