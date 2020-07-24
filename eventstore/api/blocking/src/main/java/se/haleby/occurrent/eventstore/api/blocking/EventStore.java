package se.haleby.occurrent.eventstore.api.blocking;

/**
 * Event store that should be used for "transactional" use cases. For example an ApplicationService that needs to read the current
 * event stream and pass it to a domain model and then write the result. These scenarios typically don't require advanced querying
 * capabilities and "operations" support (such as deleting events).
 */
public interface EventStore extends ReadEventStream, ConditionallyWriteToEventStream, UnconditionallyWriteToEventStream, EventStreamExists {
}
