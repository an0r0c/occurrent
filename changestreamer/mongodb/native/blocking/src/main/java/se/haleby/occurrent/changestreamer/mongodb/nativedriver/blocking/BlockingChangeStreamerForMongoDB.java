package se.haleby.occurrent.changestreamer.mongodb.nativedriver.blocking;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.ChangeStreamIterable;
import com.mongodb.client.MongoChangeStreamCursor;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import io.cloudevents.core.format.EventFormat;
import io.cloudevents.core.provider.EventFormatProvider;
import io.cloudevents.jackson.JsonFormat;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.haleby.occurrent.changestreamer.CloudEventWithStreamPosition;
import se.haleby.occurrent.changestreamer.mongodb.MongoDBFilterSpecification;
import se.haleby.occurrent.changestreamer.mongodb.MongoDBFilterSpecification.BsonMongoDBFilterSpecification;
import se.haleby.occurrent.changestreamer.mongodb.MongoDBFilterSpecification.JsonMongoDBFilterSpecification;
import se.haleby.occurrent.changestreamer.mongodb.internal.DocumentAdapter;
import se.haleby.occurrent.changestreamer.mongodb.nativedriver.blocking.RetryStrategy.Backoff;
import se.haleby.occurrent.changestreamer.mongodb.nativedriver.blocking.RetryStrategy.Fixed;
import se.haleby.occurrent.changestreamer.mongodb.nativedriver.blocking.RetryStrategy.None;
import se.haleby.occurrent.eventstore.mongodb.TimeRepresentation;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static se.haleby.occurrent.changestreamer.mongodb.internal.MongoDBCloudEventsToJsonDeserializer.deserializeToCloudEvent;

public class BlockingChangeStreamerForMongoDB {
    private static final Logger log = LoggerFactory.getLogger(BlockingChangeStreamerForMongoDB.class);

    private final MongoCollection<Document> eventCollection;
    private final ConcurrentMap<String, Subscription> subscriptions;
    private final EventFormat cloudEventSerializer;
    private final TimeRepresentation timeRepresentation;
    private final Executor cloudEventDispatcher;
    private final RetryStrategy retryStrategy;

    private volatile boolean shuttingDown = false;

    /**
     * Create a change streamer using the native MongoDB sync driver.
     *
     * @param eventCollection      The collection that contains the events
     * @param timeRepresentation   How time is represented in the databased
     * @param subscriptionExecutor The executor that will be used for the subscription. Typically a dedicated thread will be required per subscription.
     */
    public BlockingChangeStreamerForMongoDB(MongoCollection<Document> eventCollection, TimeRepresentation timeRepresentation,
                                            Executor subscriptionExecutor, RetryStrategy retryStrategy) {
        this.retryStrategy = retryStrategy;
        requireNonNull(eventCollection, "Event collection cannot be null");
        requireNonNull(timeRepresentation, "Time representation cannot be null");
        requireNonNull(subscriptionExecutor, "CloudEventDispatcher cannot  be null");
        requireNonNull(retryStrategy, "RetryStrategy cannot be null");
        this.cloudEventDispatcher = subscriptionExecutor;
        this.timeRepresentation = timeRepresentation;
        this.eventCollection = eventCollection;
        this.subscriptions = new ConcurrentHashMap<>();
        this.cloudEventSerializer = EventFormatProvider.getInstance().resolveFormat(JsonFormat.CONTENT_TYPE);
    }

    public void stream(String subscriptionId, Consumer<CloudEventWithStreamPosition<BsonDocument>> action) {
        stream(subscriptionId, action, null);
    }

    public void stream(String subscriptionId, Consumer<CloudEventWithStreamPosition<BsonDocument>> action, MongoDBFilterSpecification filter) {
        stream(subscriptionId, action, filter, Function.identity());
    }

    public void stream(String subscriptionId, Consumer<CloudEventWithStreamPosition<BsonDocument>> action, MongoDBFilterSpecification filter,
                       Function<ChangeStreamIterable<Document>, ChangeStreamIterable<Document>> changeStreamConfigurer) {
        requireNonNull(subscriptionId, "subscriptionId cannot be null");
        requireNonNull(action, "Action cannot be null");
        requireNonNull(changeStreamConfigurer, "Change stream configurer cannot be null");

        List<Bson> pipeline = createPipeline(filter);
        Runnable runnable = () -> {
            ChangeStreamIterable<Document> changeStreamDocuments = eventCollection.watch(pipeline, Document.class);
            ChangeStreamIterable<Document> changeStreamDocumentsAfterConfiguration = changeStreamConfigurer.apply(changeStreamDocuments);
            if (changeStreamDocumentsAfterConfiguration == null) {
                throw new IllegalArgumentException("Change stream configurer is not allowed to return null");
            }
            MongoChangeStreamCursor<ChangeStreamDocument<Document>> cursor = changeStreamDocumentsAfterConfiguration.cursor();

            Subscription subscription = new Subscription(subscriptionId, cursor);
            subscriptions.put(subscriptionId, subscription);

            cursor.forEachRemaining(changeStreamDocument -> deserializeToCloudEvent(cloudEventSerializer, changeStreamDocument, timeRepresentation)
                    .map(cloudEvent -> new CloudEventWithStreamPosition<>(cloudEvent, changeStreamDocument.getResumeToken()))
                    .ifPresent(retry(action, __ -> true, convertToDelayStream(retryStrategy))));
        };

        cloudEventDispatcher.execute(retry(runnable, __ -> !shuttingDown, convertToDelayStream(retryStrategy)));
    }

    private static List<Bson> createPipeline(MongoDBFilterSpecification filter) {
        final List<Bson> pipeline;
        if (filter == null) {
            pipeline = Collections.emptyList();
        } else if (filter instanceof JsonMongoDBFilterSpecification) {
            pipeline = Collections.singletonList(Document.parse(((JsonMongoDBFilterSpecification) filter).getJson()));
        } else if (filter instanceof BsonMongoDBFilterSpecification) {
            Bson[] aggregationStages = ((BsonMongoDBFilterSpecification) filter).getAggregationStages();
            DocumentAdapter documentAdapter = new DocumentAdapter(MongoClientSettings.getDefaultCodecRegistry());
            pipeline = Stream.of(aggregationStages).map(aggregationStage -> {
                final Document result;
                if (aggregationStage instanceof Document) {
                    result = (Document) aggregationStage;
                } else if (aggregationStage instanceof BsonDocument) {
                    result = documentAdapter.fromBson((BsonDocument) aggregationStage);
                } else {
                    BsonDocument bsonDocument = aggregationStage.toBsonDocument(null, MongoClientSettings.getDefaultCodecRegistry());
                    result = documentAdapter.fromBson(bsonDocument);
                }
                return result;
            }).collect(Collectors.toList());
        } else {
            throw new IllegalArgumentException("Invalid " + MongoDBFilterSpecification.class.getSimpleName());
        }
        return pipeline;
    }

    public void cancelSubscription(String subscriptionId) {
        Subscription subscription = subscriptions.remove(subscriptionId);
        if (subscription != null) {
            try {
                subscription.cursor.close();
            } catch (Exception e) {
                log.error("Failed to cancel subscription, this might happen if Mongo connection has been shutdown", e);
            }
        }
    }

    public void shutdown() {
        synchronized (subscriptions) {
            shuttingDown = true;
            subscriptions.keySet().forEach(this::cancelSubscription);
        }
    }

    private static Runnable retry(Runnable runnable, Predicate<Exception> retryPredicate, Iterator<Long> delay) {
        Consumer<Void> runnableConsumer = __ -> runnable.run();
        return () -> retry(runnableConsumer, retryPredicate, delay).accept(null);
    }

    private static <T1> Consumer<T1> retry(Consumer<T1> fn, Predicate<Exception> retryPredicate, Iterator<Long> delay) {
        return t1 -> {
            try {
                fn.accept(t1);
            } catch (Exception e) {
                if (retryPredicate.test(e) && delay != null) {
                    Long retryAfterMillis = delay.next();
                    log.error("Caught {} with message \"{}\", will retry in {} milliseconds.", e.getClass().getName(), e.getMessage(), retryAfterMillis, e);
                    try {
                        Thread.sleep(retryAfterMillis);
                    } catch (InterruptedException interruptedException) {
                        throw new RuntimeException(e);
                    }
                    retry(fn, retryPredicate, delay).accept(t1);
                } else {
                    throw e;
                }
            }
        };
    }

    private static Iterator<Long> convertToDelayStream(RetryStrategy retryStrategy) {
        final Stream<Long> delay;
        if (retryStrategy instanceof None) {
            delay = null;
        } else if (retryStrategy instanceof Fixed) {
            long millis = ((Fixed) retryStrategy).millis;
            delay = Stream.iterate(millis, __ -> millis);
        } else if (retryStrategy instanceof Backoff) {
            Backoff strategy = (Backoff) retryStrategy;
            long initialMillis = strategy.initial.toMillis();
            long maxMillis = strategy.max.toMillis();
            double multiplier = strategy.multiplier;
            delay = Stream.iterate(initialMillis, current -> Math.min(maxMillis, Math.round(current * multiplier)));
        } else {
            throw new IllegalStateException("Invalid retry strategy: " + retryStrategy.getClass().getName());
        }
        return requireNonNull(delay).iterator();
    }
}