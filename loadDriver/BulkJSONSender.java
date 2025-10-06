import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.charset.StandardCharsets.UTF_8;

// generates and sends data
public class BulkJSONSender {

    static final HttpClient CLIENT = HttpClient.newHttpClient();
    static final AtomicLong SENT_DOCS = new AtomicLong(0);
    static NumberFormat NUM_FORMAT = NumberFormat.getNumberInstance(Locale.UK);

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: java BulkJSONSender <esUrl> <indexName> <bulkSize>");
            System.exit(1);
        }

        String esUrl = args[0];
        String indexName = args[1];
        int bulkSize = Integer.parseInt(args[2]);
        int numThreads = 8;
        long totalDocs = 100_000_000L;
        long perThreadDocs = totalDocs / numThreads;
        long numberOfBulks = perThreadDocs / bulkSize;
        System.out.println("bulkSize: " + bulkSize + ", numThreads:" + numThreads + ", totalDocs:" + totalDocs
             + ", perThreadDocs:" + perThreadDocs + ", numberOfBulks:" + numberOfBulks);

        // Start periodic progress reporter
        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor();
        reporter.scheduleAtFixedRate(() -> {
            System.out.print("Documents sent: " + NUM_FORMAT.format(SENT_DOCS.get()) + "\n");
        }, 5, 5, TimeUnit.SECONDS);

        // Start threads
        long startNanos = System.nanoTime();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < numThreads; t++) {
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 0; i < numberOfBulks; i++) {
                        sendBulk(esUrl, indexName, bulkSize);
                        SENT_DOCS.addAndGet(bulkSize);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }});
            thread.start();
            threads.add(thread);
        }

        // Wait for threads to finish
        for (Thread t : threads) {
            t.join();
        }
        long elapsed = (System.nanoTime() - startNanos) / 1_000_000_000;
        reporter.shutdownNow();

        long t = SENT_DOCS.get();
        System.out.println("All documents sent: " + NUM_FORMAT.format(t) + ".");
        System.out.println("Total elapsed time: " + NUM_FORMAT.format(elapsed) + "secs");
        System.out.println("Avg docs per second: " + NUM_FORMAT.format(t / elapsed));
    }

    static String[] fields = new String[] {"myShortField", "myIntField" , "myLongField", "myKeywordField" };

    // let's move to a more loggy example - all keywords
    // { "message": "first document", "level": "info", "timestamp": "2025-10-05T10:00:00Z" }

    static void sendBulk(String esUrl, String indexName, int numDocs)
        throws IOException, InterruptedException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        for (int j = 0; j < numDocs; j++) {
            out.write("{\"index\":{}}\n".getBytes(UTF_8));
            short s = (short) j;
            int i = (int) 100 + j;
            long  l = (long) 1000 + j;
            String k = next20Letters(i);
            String line = "{" +
                "\"" + fields[0] + "\":" + s + "," +
                "\"" + fields[1] + "\":" + i + "," +
                "\"" + fields[2] + "\":" + l + "," +
                "\"" + fields[3] + "\":\"" + k + "\"}\n";
            out.write(line.getBytes(UTF_8));
        }

        // Send request
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(esUrl + "/" + indexName + "/_bulk"))
            .header("Content-Type", "application/x-ndjson")
            .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
            .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        // System.out.println("Bulk request : " + response.statusCode());
        if (response.statusCode() != 200) {
            System.err.println("Bulk request failed, response code:" + response.statusCode());
            System.err.println(response.body());
        }
    }

    static String next20Letters(int id) {
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 10; i++) {
            // There are 26 lowercase Latin letters ('a' = 97, 'z' = 122)
            int offset = (id + i) % 26;
            char c = (char) ('a' + offset);
            sb.append(c);
        }
        return sb.toString();
    }
}

