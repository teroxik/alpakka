/*
 * Copyright (C) 2016-2019 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.javadsl;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorMaterializer;
// #init-client
import akka.stream.alpakka.elasticsearch.*;
import akka.stream.alpakka.elasticsearch.javadsl.*;

// #init-client
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.testkit.javadsl.StreamTestKit;
import akka.testkit.javadsl.TestKit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.codelibs.elasticsearch.runner.ElasticsearchClusterRunner;
// #init-client
import org.elasticsearch.client.RestClient;
import org.apache.http.HttpHost;
// #init-client
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public class ElasticsearchTest {

  private static ElasticsearchClusterRunner runner;
  private static RestClient client;
  private static ActorSystem system;
  private static ActorMaterializer materializer;

  // #define-class
  public static class Book {
    public String title;

    public Book() {}

    public Book(String title) {
      this.title = title;
    }
  }
  // #define-class

  @BeforeClass
  public static void setup() throws IOException {
    runner = new ElasticsearchClusterRunner();
    runner.build(
        ElasticsearchClusterRunner.newConfigs()
            .baseHttpPort(9200)
            .baseTransportPort(9300)
            .numOfNode(1)
            .disableESLogger());
    runner.ensureYellow();

    // #init-client

    client = RestClient.builder(new HttpHost("localhost", 9201)).build();
    // #init-client

    // #init-mat
    system = ActorSystem.create();
    materializer = ActorMaterializer.create(system);
    // #init-mat

    register("source", "Akka in Action");
    register("source", "Programming in Scala");
    register("source", "Learning Scala");
    register("source", "Scala for Spark in Production");
    register("source", "Scala Puzzlers");
    register("source", "Effective Akka");
    register("source", "Akka Concurrency");
    flush("source");
  }

  @AfterClass
  public static void teardown() throws Exception {
    runner.close();
    runner.clean();
    client.close();
    TestKit.shutdownActorSystem(system);
  }

  @After
  public void checkForStageLeaks() {
    StreamTestKit.assertAllStagesStopped(materializer);
  }

  private static void flush(String indexName) throws IOException {
    client.performRequest("POST", indexName + "/_flush");
  }

  private static void register(String indexName, String title) throws IOException {
    client.performRequest(
        "POST",
        indexName + "/_doc",
        new HashMap<>(),
        new StringEntity(String.format("{\"title\": \"%s\"}", title)),
        new BasicHeader("Content-Type", "application/json"));
  }

  private void documentation() {
    // #source-settings
    ElasticsearchSourceSettings sourceSettings =
        ElasticsearchSourceSettings.create().withBufferSize(10);
    // #source-settings
    // #sink-settings
    ElasticsearchWriteSettings settings =
        ElasticsearchWriteSettings.create()
            .withBufferSize(10)
            .withVersionType("internal")
            .withRetryLogic(RetryAtFixedRate.create(5, Duration.ofSeconds(1)));
    // #sink-settings
  }

  @Test
  public void jsObjectStream() throws Exception {
    // Copy source/book to sink1/book through JsObject stream
    // #run-jsobject
    ElasticsearchSourceSettings sourceSettings = ElasticsearchSourceSettings.create();
    ElasticsearchWriteSettings sinkSettings = ElasticsearchWriteSettings.create();

    Source<ReadResult<Map<String, Object>>, NotUsed> source =
        ElasticsearchSource.create("source", "_doc", "{\"match_all\": {}}", sourceSettings, client);
    CompletionStage<Done> f1 =
        source
            .map(m -> WriteMessage.createIndexMessage(m.id(), m.source()))
            .runWith(
                ElasticsearchSink.create("sink1", "_doc", sinkSettings, client, new ObjectMapper()),
                materializer);
    // #run-jsobject

    f1.toCompletableFuture().get();

    flush("sink1");

    // Assert docs in sink1/book
    CompletionStage<List<String>> f2 =
        ElasticsearchSource.create(
                "sink1",
                "_doc",
                "{\"match_all\": {}}",
                ElasticsearchSourceSettings.create().withBufferSize(5),
                client)
            .map(m -> (String) m.source().get("title"))
            .runWith(Sink.seq(), materializer);

    List<String> result = new ArrayList<>(f2.toCompletableFuture().get());

    List<String> expect =
        Arrays.asList(
            "Akka Concurrency",
            "Akka in Action",
            "Effective Akka",
            "Learning Scala",
            "Programming in Scala",
            "Scala Puzzlers",
            "Scala for Spark in Production");

    Collections.sort(result);
    assertEquals(expect, result);
  }

  @Test
  public void typedStream() throws Exception {
    // Copy source/book to sink2/book through JsObject stream
    // #run-typed
    ElasticsearchSourceSettings sourceSettings = ElasticsearchSourceSettings.create();
    ElasticsearchWriteSettings sinkSettings = ElasticsearchWriteSettings.create();

    Source<ReadResult<Book>, NotUsed> source =
        ElasticsearchSource.typed(
            "source", "_doc", "{\"match_all\": {}}", sourceSettings, client, Book.class);
    CompletionStage<Done> f1 =
        source
            .map(m -> WriteMessage.createIndexMessage(m.id(), m.source()))
            .runWith(
                ElasticsearchSink.create("sink2", "_doc", sinkSettings, client, new ObjectMapper()),
                materializer);
    // #run-typed

    f1.toCompletableFuture().get();

    flush("sink2");

    // Assert docs in sink2/book
    CompletionStage<List<String>> f2 =
        ElasticsearchSource.typed(
                "sink2",
                "_doc",
                "{\"match_all\": {}}",
                ElasticsearchSourceSettings.create().withBufferSize(5),
                client,
                Book.class)
            .map(m -> m.source().title)
            .runWith(Sink.seq(), materializer);

    List<String> result = new ArrayList<>(f2.toCompletableFuture().get());

    List<String> expect =
        Arrays.asList(
            "Akka Concurrency",
            "Akka in Action",
            "Effective Akka",
            "Learning Scala",
            "Programming in Scala",
            "Scala Puzzlers",
            "Scala for Spark in Production");

    Collections.sort(result);
    assertEquals(expect, result);
  }

  @Test
  public void flow() throws Exception {
    // Copy source/book to sink3/book through JsObject stream
    // #run-flow
    CompletionStage<List<WriteResult<Book, NotUsed>>> f1 =
        ElasticsearchSource.typed(
                "source",
                "_doc",
                "{\"match_all\": {}}",
                ElasticsearchSourceSettings.create().withBufferSize(5),
                client,
                Book.class)
            .map(m -> WriteMessage.createIndexMessage(m.id(), m.source()))
            .via(
                ElasticsearchFlow.create(
                    "sink3",
                    "_doc",
                    ElasticsearchWriteSettings.create().withBufferSize(5),
                    client,
                    new ObjectMapper()))
            .runWith(Sink.seq(), materializer);
    // #run-flow

    List<WriteResult<Book, NotUsed>> result1 = f1.toCompletableFuture().get();
    flush("sink3");

    for (WriteResult<Book, NotUsed> aResult1 : result1) {
      assertEquals(true, aResult1.success());
    }

    // Assert docs in sink3/book
    CompletionStage<List<String>> f2 =
        ElasticsearchSource.typed(
                "sink3",
                "_doc",
                "{\"match_all\": {}}",
                ElasticsearchSourceSettings.create().withBufferSize(5),
                client,
                Book.class)
            .map(m -> m.source().title)
            .runWith(Sink.seq(), materializer);

    List<String> result2 = new ArrayList<>(f2.toCompletableFuture().get());

    List<String> expect =
        Arrays.asList(
            "Akka Concurrency",
            "Akka in Action",
            "Effective Akka",
            "Learning Scala",
            "Programming in Scala",
            "Scala Puzzlers",
            "Scala for Spark in Production");

    Collections.sort(result2);
    assertEquals(expect, result2);
  }

  @Test
  public void stringFlow() throws Exception {
    // Copy source/book to sink3/book through JsObject stream
    // #string
    String indexName = "sink3-0";
    CompletionStage<List<WriteResult<String, NotUsed>>> write =
        Source.from(
                Arrays.asList(
                    WriteMessage.createIndexMessage("1", "{\"title\": \"Das Parfum\"}"),
                    WriteMessage.createIndexMessage("2", "{\"title\": \"Faust\"}"),
                    WriteMessage.createIndexMessage(
                        "3", "{\"title\": \"Die unendliche Geschichte\"}")))
            .via(
                ElasticsearchFlow.create(
                    indexName,
                    "_doc",
                    ElasticsearchWriteSettings.create().withBufferSize(5),
                    client,
                    StringMessageWriter.getInstance()))
            .runWith(Sink.seq(), materializer);
    // #string

    List<WriteResult<String, NotUsed>> result1 = write.toCompletableFuture().get();
    flush(indexName);

    for (WriteResult<String, NotUsed> aResult1 : result1) {
      assertEquals(true, aResult1.success());
    }

    CompletionStage<List<String>> f2 =
        ElasticsearchSource.typed(
                indexName,
                "_doc",
                "{\"match_all\": {}}",
                ElasticsearchSourceSettings.create().withBufferSize(5),
                client,
                Book.class)
            .map(m -> m.source().title)
            .runWith(Sink.seq(), materializer);

    List<String> result2 = new ArrayList<>(f2.toCompletableFuture().get());

    List<String> expect = Arrays.asList("Das Parfum", "Die unendliche Geschichte", "Faust");

    Collections.sort(result2);
    assertEquals(expect, result2);
  }

  @Test
  public void testMultipleOperations() throws Exception {
    // #multiple-operations
    // Create, update, upsert and delete documents in sink8/book
    List<WriteMessage<Book, NotUsed>> requests =
        Arrays.asList(
            WriteMessage.createIndexMessage("00001", new Book("Book 1")),
            WriteMessage.createUpsertMessage("00002", new Book("Book 2")),
            WriteMessage.createUpsertMessage("00003", new Book("Book 3")),
            WriteMessage.createUpdateMessage("00004", new Book("Book 4")),
            WriteMessage.createDeleteMessage("00002"));

    Source.from(requests)
        .via(
            ElasticsearchFlow.create(
                "sink8", "_doc", ElasticsearchWriteSettings.create(), client, new ObjectMapper()))
        .runWith(Sink.seq(), materializer)
        .toCompletableFuture()
        .get();
    // #multiple-operations

    flush("sink8");

    // Assert docs in sink8/book
    CompletionStage<List<String>> f2 =
        ElasticsearchSource.typed(
                "sink8",
                "_doc",
                "{\"match_all\": {}}",
                ElasticsearchSourceSettings.create(),
                client,
                Book.class)
            .map(m -> m.source().title)
            .runWith(Sink.seq(), materializer);

    List<String> result2 = new ArrayList<>(f2.toCompletableFuture().get());
    List<String> expect = Arrays.asList("Book 1", "Book 3");
    Collections.sort(result2);

    assertEquals(expect, result2);
  }

  @Test
  public void testKafkaExample() throws Exception {
    // #kafka-example
    // We're going to pretend we got messages from kafka.
    // After we've written them to Elastic, we want
    // to commit the offset to Kafka

    List<KafkaMessage> messagesFromKafka =
        Arrays.asList(
            new KafkaMessage(new Book("Book 1"), new KafkaOffset(0)),
            new KafkaMessage(new Book("Book 2"), new KafkaOffset(1)),
            new KafkaMessage(new Book("Book 3"), new KafkaOffset(2)));

    final KafkaCommitter kafkaCommitter = new KafkaCommitter();

    CompletionStage<Done> kafkaToEs =
        Source.from(messagesFromKafka) // Assume we get this from Kafka
            .map(
                kafkaMessage -> {
                  Book book = kafkaMessage.book;
                  String id = book.title;

                  // Transform message so that we can write to elastic
                  return WriteMessage.createIndexMessage(id, book)
                      .withPassThrough(kafkaMessage.offset);
                })
            .via( // write to elastic
                ElasticsearchFlow.createWithPassThrough(
                    "sink6",
                    "_doc",
                    ElasticsearchWriteSettings.create().withBufferSize(5),
                    client,
                    new ObjectMapper()))
            .map(
                result -> {
                  if (!result.success())
                    throw new RuntimeException("Failed to write message to elastic");
                  // Commit to kafka
                  kafkaCommitter.commit(result.message().passThrough());
                  return NotUsed.getInstance();
                })
            .runWith(Sink.ignore(), materializer);
    // #kafka-example
    kafkaToEs.toCompletableFuture().get(5, TimeUnit.SECONDS); // Wait for it to complete
    flush("sink6");

    // Make sure all messages was committed to kafka
    assertEquals(Arrays.asList(0, 1, 2), kafkaCommitter.committedOffsets);

    // Assert that all docs were written to elastic
    List<String> result2 =
        ElasticsearchSource.typed(
                "sink6",
                "_doc",
                "{\"match_all\": {}}",
                ElasticsearchSourceSettings.create(),
                client,
                Book.class)
            .map(m -> m.source().title)
            .runWith(Sink.seq(), materializer) // Run it
            .toCompletableFuture()
            .get(); // Wait for it to complete

    assertEquals(
        messagesFromKafka.stream().map(m -> m.book.title).sorted().collect(Collectors.toList()),
        result2.stream().sorted().collect(Collectors.toList()));
  }

  @Test
  public void testUsingVersions() throws Exception {
    // Since the scala-test does a lot more logic testing,
    // all we need to test here is that we can receive and send version

    String indexName = "test_using_versions";
    String typeName = "_doc";

    // Insert document
    Book book = new Book("b");
    Source.single(WriteMessage.createIndexMessage("1", book))
        .via(
            ElasticsearchFlow.create(
                indexName,
                typeName,
                ElasticsearchWriteSettings.create().withBufferSize(5),
                client,
                new ObjectMapper()))
        .runWith(Sink.seq(), materializer)
        .toCompletableFuture()
        .get();

    flush(indexName);

    // Search document and assert it having version 1
    ReadResult<Book> message =
        ElasticsearchSource.<Book>typed(
                indexName,
                typeName,
                "{\"match_all\": {}}",
                ElasticsearchSourceSettings.create().withIncludeDocumentVersion(true),
                client,
                Book.class)
            .runWith(Sink.head(), materializer)
            .toCompletableFuture()
            .get();

    assertEquals(1L, message.version().get());

    flush(indexName);

    // Update document to version 2
    Source.single(WriteMessage.createIndexMessage("1", book).withVersion(1L))
        .via(
            ElasticsearchFlow.create(
                indexName,
                typeName,
                ElasticsearchWriteSettings.create().withBufferSize(5),
                client,
                new ObjectMapper()))
        .runWith(Sink.seq(), materializer)
        .toCompletableFuture()
        .get();

    flush(indexName);

    // Try to update document with wrong version to assert that we can send it
    long oldVersion = 1;
    boolean success =
        Source.single(WriteMessage.createIndexMessage("1", book).withVersion(oldVersion))
            .via(
                ElasticsearchFlow.create(
                    indexName,
                    typeName,
                    ElasticsearchWriteSettings.create().withBufferSize(5),
                    client,
                    new ObjectMapper()))
            .runWith(Sink.seq(), materializer)
            .toCompletableFuture()
            .get()
            .get(0)
            .success();

    assertEquals(false, success);
  }

  @Test
  public void testUsingVersionType() throws Exception {
    String indexName = "book-test-version-type";
    String typeName = "_doc";

    Book book = new Book("A sample title");
    String docId = "1";
    long externalVersion = 5;

    // Insert new document using external version
    Source.single(WriteMessage.createIndexMessage("1", book).withVersion(externalVersion))
        .via(
            ElasticsearchFlow.create(
                indexName,
                typeName,
                ElasticsearchWriteSettings.create().withBufferSize(5).withVersionType("external"),
                client,
                new ObjectMapper()))
        .runWith(Sink.seq(), materializer)
        .toCompletableFuture()
        .get();

    flush(indexName);

    // Assert that the document's external version is saved
    ReadResult<Book> message =
        ElasticsearchSource.<Book>typed(
                indexName,
                typeName,
                "{\"match_all\": {}}",
                ElasticsearchSourceSettings.create().withIncludeDocumentVersion(true),
                client,
                Book.class)
            .runWith(Sink.seq(), materializer)
            .toCompletableFuture()
            .get()
            .get(0);

    assertEquals(externalVersion, message.version().get());
  }

  // #custom-search-params
  public static class TestDoc {
    public String id;
    public String a;
    public String b;
    public String c;

    // #custom-search-params
    public TestDoc() {}

    public TestDoc(String id, String a, String b, String c) {
      this.id = id;
      this.a = a;
      this.b = b;
      this.c = c;
    }
    // #custom-search-params
  }
  // #custom-search-params

  @Test
  public void testUsingSearchParams() throws Exception {

    String indexName = "test_using_search_params_versions_java";
    String typeName = "_doc";

    List<TestDoc> docs =
        Arrays.asList(
            new TestDoc("1", "a1", "b1", "c1"),
            new TestDoc("2", "a2", "b2", "c2"),
            new TestDoc("3", "a3", "b3", "c3"));

    // Insert document
    Source.from(docs)
        .map((TestDoc d) -> WriteMessage.createIndexMessage(d.id, d))
        .via(
            ElasticsearchFlow.create(
                indexName,
                typeName,
                ElasticsearchWriteSettings.create().withBufferSize(5),
                client,
                new ObjectMapper()))
        .runWith(Sink.seq(), materializer)
        .toCompletableFuture()
        .get();

    flush(indexName);

    // #custom-search-params
    // Search for docs and ask elastic to only return some fields

    Map<String, String> searchParams = new HashMap<>();
    searchParams.put("query", "{\"match_all\": {}}");
    searchParams.put("_source", "[\"id\", \"a\", \"c\"]");

    List<TestDoc> result =
        ElasticsearchSource.<TestDoc>typed(
                indexName,
                typeName,
                searchParams, // <-- Using searchParams
                ElasticsearchSourceSettings.create(),
                client,
                TestDoc.class,
                new ObjectMapper())
            .map(
                o -> {
                  return o.source(); // These documents will only have property id, a and c (not b)
                })
            .runWith(Sink.seq(), materializer)
            .toCompletableFuture()
            .get();
    // #custom-search-params
    flush(indexName);

    assertEquals(
        docs.size(),
        result.stream()
            .filter(
                d -> {
                  return d.a != null && d.b == null;
                })
            .collect(Collectors.toList())
            .size());
  }

  static class KafkaCommitter {
    List<Integer> committedOffsets = new ArrayList<>();

    public KafkaCommitter() {}

    void commit(KafkaOffset offset) {
      committedOffsets.add(offset.offset);
    }
  }

  static class KafkaOffset {
    final int offset;

    public KafkaOffset(int offset) {
      this.offset = offset;
    }
  }

  static class KafkaMessage {
    final Book book;
    final KafkaOffset offset;

    public KafkaMessage(Book book, KafkaOffset offset) {
      this.book = book;
      this.offset = offset;
    }
  }

  public void compileOnlySample() {
    String doc = "dummy-doc";

    // #custom-index-name-example
    WriteMessage msg = WriteMessage.createIndexMessage(doc).withIndexName("my-index");
    // #custom-index-name-example

    // #custom-metadata-example
    Map<String, String> metadata = new HashMap<>();
    metadata.put("pipeline", "myPipeline");
    WriteMessage msgWithMetadata =
        WriteMessage.createIndexMessage(doc).withCustomMetadata(metadata);
    // #custom-metadata-example
  }
}
