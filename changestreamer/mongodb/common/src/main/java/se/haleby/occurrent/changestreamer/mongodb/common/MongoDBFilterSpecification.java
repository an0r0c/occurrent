package se.haleby.occurrent.changestreamer.mongodb.common;

import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.function.BiFunction;

import static com.mongodb.client.model.Aggregates.match;

/**
 * Add filters when subscribing to a MongoDB change streamer if you're only interested in specify changes.
 */
public class MongoDBFilterSpecification {

    public static final String FULL_DOCUMENT = "fullDocument";

    public static class JsonMongoDBFilterSpecification extends MongoDBFilterSpecification {
        private final String json;

        public JsonMongoDBFilterSpecification(String json) {
            this.json = json;
        }

        public String getJson() {
            return json;
        }

        public static JsonMongoDBFilterSpecification filter(String json) {
            return new JsonMongoDBFilterSpecification(json);
        }
    }

    /**
     * Supply a document filter. For example if using Spring you can do:
     * <pre>
     * filter(where("type").is("MyEventType"))
     * </pre>
     * <p>
     * Where <code>where</code> is imported from the <code>org.springframework.data.mongodb.core.query.Criteria</code> api.
     */
    public static class DocumentMongoDBFilterSpecification extends MongoDBFilterSpecification {
        private final Document[] documents;

        public DocumentMongoDBFilterSpecification(Document document, Document... documents) {
            this.documents = new Document[1 + documents.length];
            documents[0] = document;
            System.arraycopy(documents, 0, this.documents, 1, documents.length);
        }

        public Document[] getDocuments() {
            return documents;
        }

        public static DocumentMongoDBFilterSpecification filter(Document document, Document... documents) {
            return new DocumentMongoDBFilterSpecification(document, documents);
        }
    }

    /**
     * Use e.g. {@link Filters} to create a bson filter. Note that MongoDB wraps the cloud event in a document called {@value FULL_DOCUMENT}
     * so you need to take this into account when creating custom filters. Note also that each filter entry must be a valid
     * <a href="https://docs.mongodb.com/manual/reference/operator/aggregation-pipeline/">aggregation stage</a>.
     * For more simple filters use the predefined filter methods such as {@link #id(BiFunction, String)} and {@link #type(BiFunction, String)}.
     * <br/>
     * <br/>
     * <p>
     * Examples:
     *
     * <pre>
     * filter().type(Filters::eq, "12345").and().data("someInt", Filters::lt, 3))
     * </pre>
     * <p>
     * which can be written like this if created manually:
     *
     * <pre>
     * filter(match(eq("fullDocument.id", "12345")), matches(lt("fullDocument.data.someInt", 3)))))
     * </pre>
     * </p>
     */
    public static class BsonMongoDBFilterSpecification extends MongoDBFilterSpecification {

        private final Bson[] aggregationStages;

        private BsonMongoDBFilterSpecification() {
            this.aggregationStages = new Bson[0];
        }

        public BsonMongoDBFilterSpecification(Bson firstAggregationStage, Bson... additionalStages) {
            this(new Bson[]{firstAggregationStage}, additionalStages);
        }

        private BsonMongoDBFilterSpecification(Bson[] firstAggregationStage, Bson... additionalStages) {
            this.aggregationStages = new Bson[firstAggregationStage.length + additionalStages.length];
            System.arraycopy(firstAggregationStage, 0, this.aggregationStages, 0, firstAggregationStage.length);
            System.arraycopy(additionalStages, 0, this.aggregationStages, firstAggregationStage.length, additionalStages.length);
        }

        public static BsonMongoDBFilterSpecification filter(Bson firstAggregationStage, Bson... additionalStages) {
            return new BsonMongoDBFilterSpecification(firstAggregationStage, additionalStages);
        }

        public static BsonMongoDBFilterSpecification filter() {
            return new BsonMongoDBFilterSpecification();

        }

        public BsonMongoDBFilterSpecification and() {
            return this;
        }

        public BsonMongoDBFilterSpecification id(BiFunction<String, String, Bson> filter, String value) {
            return new BsonMongoDBFilterSpecification(aggregationStages, matchStage(filter, "id", value));
        }

        public BsonMongoDBFilterSpecification type(BiFunction<String, String, Bson> filter, String value) {
            return new BsonMongoDBFilterSpecification(aggregationStages, matchStage(filter, "type", value));
        }

        public BsonMongoDBFilterSpecification source(BiFunction<String, String, Bson> filter, String value) {
            return new BsonMongoDBFilterSpecification(aggregationStages, matchStage(filter, "source", value));
        }

        public BsonMongoDBFilterSpecification subject(BiFunction<String, String, Bson> filter, String value) {
            return new BsonMongoDBFilterSpecification(aggregationStages, matchStage(filter, "subject", value));
        }

        public BsonMongoDBFilterSpecification specVersion(BiFunction<String, String, Bson> filter, String value) {
            return new BsonMongoDBFilterSpecification(aggregationStages, matchStage(filter, "specversion", value));
        }

        public BsonMongoDBFilterSpecification dataContentType(BiFunction<String, String, Bson> filter, String value) {
            return new BsonMongoDBFilterSpecification(aggregationStages, matchStage(filter, "datacontenttype", value));
        }

        public BsonMongoDBFilterSpecification time(BiFunction<String, String, Bson> filter, String value) {
            return new BsonMongoDBFilterSpecification(aggregationStages, matchStage(filter, "time", value));
        }

        public BsonMongoDBFilterSpecification occurrentStreamId(BiFunction<String, String, Bson> filter, String value) {
            return new BsonMongoDBFilterSpecification(aggregationStages, matchStage(filter, "occurrentStreamId", value));
        }

        public BsonMongoDBFilterSpecification extension(BiFunction<String, String, Bson> filter, String propertyName, String value) {
            return new BsonMongoDBFilterSpecification(aggregationStages, matchStage(filter, propertyName, value));
        }

        public BsonMongoDBFilterSpecification data(BiFunction<String, String, Bson> filter, String propertyName, String value) {
            return new BsonMongoDBFilterSpecification(aggregationStages, matchStage(filter, "data." + propertyName, value));
        }

        private static Bson matchStage(BiFunction<String, String, Bson> filter, String propertyName, String value) {
            return match(filter.apply(FULL_DOCUMENT + "." + propertyName, value));
        }

        public Bson[] getAggregationStages() {
            return aggregationStages;
        }
    }
}