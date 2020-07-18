package se.haleby.occurrent.example.eventstore.mongodb.spring.changestreamedprojections;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.messaging.DefaultMessageListenerContainer;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import se.haleby.occurrent.changestreamer.mongodb.spring.blocking.SpringBlockingChangeStreamerForMongoDB;
import se.haleby.occurrent.eventstore.api.blocking.EventStore;
import se.haleby.occurrent.eventstore.mongodb.spring.blocking.SpringBlockingMongoEventStore;
import se.haleby.occurrent.eventstore.mongodb.spring.blocking.StreamConsistencyGuarantee;

@SpringBootApplication
@EnableMongoRepositories
public class ChangeStreamedProjectionsWithSpringAndMongoDBApplication {

    private static final String EVENTS_COLLECTION = "events";

    @Bean
    public EventStore eventStore(MongoTemplate mongoTemplate) {
        return new SpringBlockingMongoEventStore(mongoTemplate, EVENTS_COLLECTION, StreamConsistencyGuarantee.none());
    }

    @Bean
    public SpringBlockingChangeStreamerForMongoDB springBlockingChangeStreamerForMongoDB(MongoTemplate mongoTemplate) {
        return new SpringBlockingChangeStreamerForMongoDB(mongoTemplate, EVENTS_COLLECTION, "event-subscribers", new DefaultMessageListenerContainer(mongoTemplate));
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}