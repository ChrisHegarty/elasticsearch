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
public class BulkCSVSender {

    static final HttpClient CLIENT = HttpClient.newHttpClient();
    static final AtomicLong SENT_DOCS = new AtomicLong(0);
    static NumberFormat NUM_FORMAT = NumberFormat.getNumberInstance(Locale.UK);

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("Usage: java BulkCSVSender <esUrl> <indexName> <bulkSize>");
            System.exit(1);
        }

        String esUrl = args[0];
        String indexName = args[1];
        int bulkSize = Integer.parseInt(args[2]);
        // Divide contiguous blocks of bulk ranges among threads
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

    //curl -X POST "http://localhost:9200/logs/_doc" \
    //  -H 'Content-Type: application/json' \
    //  -d \
    //'myShortField,myIntField,myLongField,myKeywordField
    //short,int,long,keyword
    //101,201,301,A
    //102,202,302,B
    //103,203,303,C'  | jq

    static String headerLine = "myShortField,myIntField,myLongField,myKeywordField\n";
    static String typeLine = "short,int,long,keyword\n";

    static void sendBulk(String esUrl, String indexName, int numDocs)
        throws IOException, InterruptedException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        out.write(headerLine.getBytes(UTF_8));
        out.write(typeLine.getBytes(UTF_8));

        for (int j = 0; j < numDocs; j++) {
            short s = (short) j;
            int i = (int) 100 + j;
            long  l = (long) 1000 + j;
            String k = next20Letters(i);
            String line = s + "," + i + "," + l + "," + k + "\n";
            out.write(line.getBytes(UTF_8));
        }

        // Send request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(esUrl + "/" + indexName + "/_doc"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        // System.out.println("Bulk request : " + response.statusCode());
        if (response.statusCode() != 201) {
            System.err.println("Bulk request failed.");
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

