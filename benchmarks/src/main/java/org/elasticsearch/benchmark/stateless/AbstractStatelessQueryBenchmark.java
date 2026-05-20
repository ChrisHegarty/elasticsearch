/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.stateless;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.elasticsearch.benchmark.Utils;
import org.elasticsearch.blobcache.shared.SharedBlobCacheService;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.xpack.stateless.lucene.StatelessDirectoryFactory;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Base class for benchmarks that run Lucene queries against a stateless-simulated
 * Directory and compare cold-cache vs. hot-cache cost.
 *
 * <p>Each invocation runs a single query against a freshly-created
 * {@link StatelessDirectoryFactory} wrapper around an on-disk index built once
 * at trial setup. For {@link CacheState#HOT} the cache is sequentially warmed
 * before the query; for {@link CacheState#COLD} it is left empty so the query
 * exercises the simulated blob-store fetch path.
 *
 * <p>Per-invocation cache stats are exposed via {@link CacheCounters} as JMH
 * auxiliary counters: {@code bytesRead} (bytes the query consumed from the
 * cache layer) and {@code bytesDownloaded} (bytes the cache had to fetch from
 * the simulated blob store, i.e. the bytes that incurred the configured
 * first-byte latency).
 *
 * <p>To add a new query benchmark, extend this class and implement
 * {@link #indexWriterConfig()}, {@link #buildIndex(IndexWriter)} and
 * {@link #runQuery(IndexSearcher)}. Add {@link Param} fields on the concrete
 * subclass to sweep query-specific knobs (e.g. dimensionality, top-K).
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 20)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public abstract class AbstractStatelessQueryBenchmark {

    static {
        Utils.configureBenchmarkLogging();
        var nativeAccess = org.elasticsearch.nativeaccess.NativeAccess.instance();
        System.err.println("[stateless-bench] NativeAccess: " + nativeAccess.getClass().getName());
    }

    public enum CacheState {
        COLD,
        HOT
    }

    @Param({ "COLD", "HOT" })
    public CacheState cacheState;

    @Param({ "0" })
    public long firstByteLatencyMs;

    @Param({ "false" })
    public boolean dropOsPageCache;

    private Path dataPath;
    private Path workPath;
    protected Directory directory;
    protected IndexReader reader;
    protected IndexSearcher searcher;

    @Setup(Level.Trial)
    public final void setupTrial() throws IOException {
        String cacheKey = indexCacheKey();
        if (cacheKey == null) {
            dataPath = Files.createTempDirectory("stateless-bench-data");
            buildIndexInto(dataPath);
        } else {
            dataPath = Path.of(System.getProperty("java.io.tmpdir"), "stateless-bench-index", cacheKey);
            Files.createDirectories(dataPath);
            Path marker = dataPath.resolve("_built");
            if (Files.exists(marker) == false) {
                buildIndexInto(dataPath);
                Files.createFile(marker);
            }
        }
        workPath = Files.createTempDirectory("stateless-bench-work");
        prepareQuery();

        System.setProperty(StatelessDirectoryFactory.FIRST_BYTE_LATENCY_MS_PROP, Long.toString(firstByteLatencyMs));
        directory = StatelessDirectoryFactory.create(dataPath, workPath);
        if (cacheState == CacheState.HOT) {
            preWarm(directory);
        }
        reader = DirectoryReader.open(directory);
        searcher = new IndexSearcher(reader);
    }

    private void buildIndexInto(Path path) throws IOException {
        System.err.println("[stateless-bench] building index at " + path);
        try (Directory d = FSDirectory.open(path); IndexWriter w = new IndexWriter(d, indexWriterConfig())) {
            buildIndex(w);
            w.commit();
        }
        System.err.println("[stateless-bench] building index at " + path + " complete");
    }

    @Setup(Level.Invocation)
    public final void setupInvocation() throws IOException {
        if (dropOsPageCache) {
            evictSharedCachePages(workPath);
        }
    }

    @TearDown(Level.Trial)
    public final void tearDownTrial() throws IOException {
        IOUtils.close(reader, directory);
        if (indexCacheKey() == null) {
            deleteRecursively(dataPath);
        }
        deleteRecursively(workPath);
    }

    @Benchmark
    public final Object runBenchmark(CacheCounters counters) throws IOException {
        SharedBlobCacheService.Stats before = StatelessDirectoryFactory.statsFor(directory);
        long[] faultsBefore = readPageFaults();
        Object result = runQuery(searcher);
        long[] faultsAfter = readPageFaults();
        SharedBlobCacheService.Stats after = StatelessDirectoryFactory.statsFor(directory);
        if (before != null && after != null) {
            counters.bytesRead = after.readBytes() - before.readBytes();
            counters.bytesDownloaded = after.writeBytes() - before.writeBytes();
            counters.cacheMisses = after.missCount() - before.missCount();
            counters.regionWrites = after.writeCount() - before.writeCount();
        }
        counters.minorFaults = faultsAfter[0] - faultsBefore[0];
        counters.majorFaults = faultsAfter[1] - faultsBefore[1];
        return result;
    }

    private static long[] readPageFaults() {
        try {
            String stat = Files.readString(Path.of("/proc/self/stat"));
            String[] fields = stat.split(" ");
            long minorFaults = Long.parseLong(fields[9]);
            long majorFaults = Long.parseLong(fields[11]);
            return new long[] { minorFaults, majorFaults };
        } catch (Exception e) {
            return new long[] { 0, 0 };
        }
    }

    /** Subclass hook: the {@link IndexWriterConfig} (codec, merge policy) used to build the index. */
    protected abstract IndexWriterConfig indexWriterConfig();

    /** Subclass hook: write documents into the index. Called once per trial (skipped when a cached index is reused). */
    protected abstract void buildIndex(IndexWriter writer) throws IOException;

    /** Subclass hook: execute the query. Returns a value that JMH can consume to prevent dead-code elimination. */
    protected abstract Object runQuery(IndexSearcher searcher) throws IOException;

    /**
     * Subclass hook: stable identifier for the index built by {@link #buildIndex(IndexWriter)} given the current
     * {@code @Param} values. When non-null, the index is built on-disk at a stable location and reused across trials
     * whose params produce the same key (e.g. cold vs. hot for the same {@code dims}/{@code numDocs}). Return null
     * to keep the default behavior of building a fresh index per trial.
     */
    protected String indexCacheKey() {
        return null;
    }

    /** Subclass hook: populate query-side state. Runs once per trial, after the (possibly cached) index is ready. */
    protected void prepareQuery() throws IOException {}

    private static final long BALLAST_SIZE = Long.getLong("es.bench.ballastBytes", 5L * 1024 * 1024 * 1024);

    private static void evictSharedCachePages(Path workPath) throws IOException {
        // 1. Tell the kernel to drop cached pages for the shared bytes file
        try (Stream<Path> walk = Files.walk(workPath)) {
            walk.filter(p -> p.getFileName().toString().equals("shared_snapshot_cache")).forEach(p -> {
                try {
                    new ProcessBuilder("vmtouch", "-e", p.toString()).inheritIO().start().waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    System.err.println("[stateless-bench] WARNING: failed to evict pages for " + p + ": " + e.getMessage());
                }
            });
        }
        // 2. Force physical page reclamation by filling RAM with a throwaway mmap.
        //    This pushes the blob cache pages out of physical memory, ensuring
        //    subsequent accesses trigger real major faults from disk.
        if (BALLAST_SIZE > 0) {
            Path ballast = workPath.resolve("ballast");
            try (
                RandomAccessFile raf = new RandomAccessFile(ballast.toFile(), "rw");
                Arena arena = Arena.ofConfined()
            ) {
                raf.setLength(BALLAST_SIZE);
                MemorySegment seg = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, BALLAST_SIZE, arena);
                for (long i = 0; i < BALLAST_SIZE; i += 4096) {
                    seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, i, (byte) 1);
                }
            }
            Files.deleteIfExists(ballast);
        }
    }

    private static void preWarm(Directory dir) throws IOException {
        System.err.println("[stateless-bench] prewarm index at " + dir);
        byte[] buf = new byte[64 * 1024];
        for (String name : dir.listAll()) {
            try (IndexInput in = dir.openInput(name, IOContext.READONCE)) {
                long remaining = in.length();
                while (remaining > 0) {
                    int n = (int) Math.min(buf.length, remaining);
                    in.readBytes(buf, 0, n);
                    remaining -= n;
                }
            }
        }
        System.err.println("[stateless-bench] prewarm index at " + dir + " complete");
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path) == false) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOExceptionWrapper(e);
                }
            });
        } catch (UncheckedIOExceptionWrapper e) {
            throw e.getCause();
        }
    }

    private static final class UncheckedIOExceptionWrapper extends RuntimeException {
        UncheckedIOExceptionWrapper(IOException cause) {
            super(cause);
        }

        @Override
        public synchronized IOException getCause() {
            return (IOException) super.getCause();
        }
    }

    /**
     * JMH auxiliary counters reported per invocation: bytes read from the cache layer
     * and bytes downloaded by the cache from the simulated blob store.
     */
    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.EVENTS)
    public static class CacheCounters {
        public long bytesRead;
        public long bytesDownloaded;
        public long cacheMisses;
        public long regionWrites;
        public long minorFaults;
        public long majorFaults;

        @Setup(Level.Invocation)
        public void reset() {
            bytesRead = 0;
            bytesDownloaded = 0;
            cacheMisses = 0;
            regionWrites = 0;
            minorFaults = 0;
            majorFaults = 0;
        }
    }
}
