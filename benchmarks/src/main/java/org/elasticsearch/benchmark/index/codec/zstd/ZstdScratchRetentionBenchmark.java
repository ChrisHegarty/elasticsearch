/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.benchmark.index.codec.zstd;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.compressing.Compressor;
import org.apache.lucene.codecs.compressing.Decompressor;
import org.apache.lucene.store.ByteBuffersDataInput;
import org.apache.lucene.store.ByteBuffersDataOutput;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.benchmark.internal.BenchmarkLogging;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.index.codec.zstd.ZstdCompressionMode;
import org.elasticsearch.index.store.StoreMetrics;
import org.elasticsearch.index.store.StoreMetricsIndexInput;
import org.elasticsearch.index.store.ThreadLocalDirectoryMetricHolder;
import org.elasticsearch.xpack.searchablesnapshots.store.SearchableSnapshotDirectoryFactory;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Investigation benchmark for native-memory retention on the zstd copy-fallback read path (seceng#16254).
 *
 * <p>Many threads decompress random blocks through a real {@code ZstdCompressionMode} decompressor. The input types are:
 * <ul>
 *   <li>{@code NIO_STORE_METRICS}: a plain NIOFS input wrapped as {@code Store} does for every shard. The wrapper is a
 *       {@code DirectAccessInput} that has no slice to give, so every read takes the copying fallback and allocates its
 *       scratch segment from {@code Arena.ofAuto()}. This is what fully mounted searchable snapshots do in production.</li>
 *   <li>{@code NIO}: a plain NIOFS input. Not a slice-capable input, so it decompresses from a heap copy. Control.</li>
 *   <li>{@code MMAP}: zero-copy. Control.</li>
 *   <li>{@code SNAP_FULL_COPY}: a fully mounted searchable snapshot (cold tier), so the real {@code CachedBlobContainerIndexInput}.</li>
 *   <li>{@code SNAP_FROZEN_MMAP}, {@code SNAP_FROZEN_NOMMAP}, {@code SNAP_FROZEN_NOMMAP_MISS}: a partially mounted searchable
 *       snapshot (frozen tier), so the real {@code FrozenIndexInput}, with an mmap-backed cache that holds the file (zero-copy on a
 *       hit), a non-mmap cache that holds the file (copy into scratch), and a non-mmap cache much smaller than the file (reads miss
 *       and are filled by the cache fetch threads through the scratch segment).</li>
 * </ul>
 * The snapshot inputs are wrapped in {@code StoreMetricsIndexInput}, as {@code Store} does in production.
 *
 * <p>It does not report throughput as its main output. While it runs, a sampler thread prints one {@code SAMPLE} line
 * every few seconds (RssAnon, the direct buffer pool, GC counts), and at the end of the trial it prints the glibc
 * {@code malloc_info} totals, trims the native heap, and prints how much the trim released. Use {@code -t 64} (or
 * similar) for threads and run on Linux/glibc, with a large heap so that young GCs are rare.
 */
@Fork(value = 1, jvmArgsPrepend = { "--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector" })
@Warmup(iterations = 1, time = 10)
@Measurement(iterations = 6, time = 20)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Threads(1)
public class ZstdScratchRetentionBenchmark {

    private static final int NUM_BLOCKS = 4096;
    private static final String FILE_NAME = "blocks.dat";

    public enum InputType {
        NIO,
        NIO_STORE_METRICS,
        MMAP,
        /** Fully mounted (cold, {@code full_copy}): {@code CachedBlobContainerIndexInput}, wrapped in {@code StoreMetricsIndexInput}. */
        SNAP_FULL_COPY,
        /** Partially mounted (frozen), mmap-backed cache that holds the whole file: {@code FrozenIndexInput}, zero-copy on a hit. */
        SNAP_FROZEN_MMAP,
        /** Partially mounted (frozen), non-mmap cache that holds the whole file: every read is copied into the scratch segment. */
        SNAP_FROZEN_NOMMAP,
        /** Partially mounted (frozen), non-mmap cache much smaller than the file: most reads miss and are filled by fetch threads. */
        SNAP_FROZEN_NOMMAP_MISS
    }

    @Param({ "NIO_STORE_METRICS", "NIO" })
    InputType inputType;

    @Param({ "6144" })
    int blockSize;

    @Param({ "5" })
    int sampleSeconds;

    /** Total target reads per second across all threads; 0 means unthrottled (which overwhelms the Cleaner thread). */
    @Param({ "0" })
    int targetOpsPerSec;

    @Param({ "1" })
    int threadsForPacing;

    /** Size of the shared blob cache for {@code SNAP_FROZEN_NOMMAP_MISS}; the file is about 25 MiB, so most reads miss. */
    @Param({ "4" })
    int missCacheMiB;

    /** Region (and range) size of the shared blob cache for {@code SNAP_FROZEN_NOMMAP_MISS}. */
    @Param({ "256" })
    int missRegionKiB;

    Directory dir;
    IndexInput input;
    long[] blockOffsets;
    Path tempDir;
    ZstdCompressionMode mode;
    private Thread sampler;
    private volatile boolean stopSampler;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        BenchmarkLogging.configure();
        tempDir = Files.createTempDirectory("zstd-retention");
        mode = new ZstdCompressionMode(1);
        blockOffsets = new long[NUM_BLOCKS];
        Random rng = new Random(42);
        dir = newDirectory();
        try (IndexOutput out = dir.createOutput(FILE_NAME, IOContext.DEFAULT)) {
            for (int i = 0; i < NUM_BLOCKS; i++) {
                byte[] original = new byte[blockSize];
                rng.nextBytes(original);
                byte[] compressed = compress(mode, original);
                blockOffsets[i] = out.getFilePointer();
                out.writeBytes(compressed, compressed.length);
            }
            CodecUtil.writeFooter(out);
        }
        IndexInput in = dir.openInput(FILE_NAME, IOContext.DEFAULT);
        // Store wraps every shard directory in StoreMetricsDirectory, so the inputs a shard sees are wrapped too
        input = inputType == InputType.NIO_STORE_METRICS || isSnapshot()
            ? StoreMetricsIndexInput.create(in.toString(), in, new ThreadLocalDirectoryMetricHolder<>(StoreMetrics::new).singleThreaded())
            : in;
        if (isSnapshot() && inputType != InputType.SNAP_FROZEN_NOMMAP_MISS) {
            warmCache(in);
        }
        System.err.printf(
            Locale.ROOT,
            "TIER1 setup inputType=%s inputClass=%s delegateClass=%s blockSize=%d fileLength=%d%n",
            inputType,
            input.getClass().getName(),
            in.getClass().getName(),
            blockSize,
            in.length()
        );
        startSampler();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        stopSampler = true;
        sampler.join();
        long rssBefore = rssAnonMiB();
        String info = diagnostic("systemNativeHeapInfo");
        System.err.println("TIER1 glibc (before trim): " + summarize(info));
        String trim = diagnostic("systemTrimNativeHeap");
        System.err.println("TIER1 trim: " + trim.trim().replace('\n', ' '));
        System.err.printf(Locale.ROOT, "TIER1 RssAnon MiB before trim=%d after trim=%d%n", rssBefore, rssAnonMiB());
        IOUtils.close(input, dir);
        IOUtils.rm(tempDir);
    }

    /** Per-thread decompression state: decompressors and the BytesRef are not thread-safe. */
    @State(Scope.Thread)
    public static class ThreadState {
        IndexInput in;
        Decompressor decompressor;
        BytesRef bytes = new BytesRef();
        SplittableRandom rnd;
        long periodNanos;
        long nextNanos;

        void init(ZstdScratchRetentionBenchmark bench) {
            in = bench.input.clone();
            decompressor = bench.mode.newDecompressor();
            rnd = new SplittableRandom(Thread.currentThread().threadId());
            if (bench.targetOpsPerSec > 0) {
                periodNanos = (long) (1e9 * bench.threadsForPacing / bench.targetOpsPerSec);
                nextNanos = System.nanoTime() + rnd.nextLong(periodNanos);
            }
        }
    }

    @Benchmark
    public void decompress(ThreadState ts, ZstdScratchRetentionBenchmark bench, Blackhole bh) throws IOException {
        if (ts.in == null) {
            ts.init(bench);
        }
        ts.in.seek(bench.blockOffsets[ts.rnd.nextInt(NUM_BLOCKS)]);
        ts.decompressor.decompress(ts.in, bench.blockSize, 0, bench.blockSize, ts.bytes);
        bh.consume(ts.bytes.bytes);
        if (ts.periodNanos > 0) {
            ts.nextNanos += ts.periodNanos;
            long wait = ts.nextNanos - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            } else {
                ts.nextNanos = System.nanoTime();
            }
        }
    }

    private boolean isSnapshot() {
        return switch (inputType) {
            case SNAP_FULL_COPY, SNAP_FROZEN_MMAP, SNAP_FROZEN_NOMMAP, SNAP_FROZEN_NOMMAP_MISS -> true;
            default -> false;
        };
    }

    private Directory newDirectory() throws IOException {
        Path path = Files.createDirectories(tempDir.resolve("data"));
        return switch (inputType) {
            case NIO, NIO_STORE_METRICS -> new NIOFSDirectory(path);
            case MMAP -> new MMapDirectory(path);
            case SNAP_FULL_COPY -> SearchableSnapshotDirectoryFactory.newDirectory(
                path,
                new SearchableSnapshotDirectoryFactory.Options(false, false, null, null)
            );
            case SNAP_FROZEN_MMAP -> SearchableSnapshotDirectoryFactory.newDirectory(
                path,
                new SearchableSnapshotDirectoryFactory.Options(true, true, null, null)
            );
            case SNAP_FROZEN_NOMMAP -> SearchableSnapshotDirectoryFactory.newDirectory(
                path,
                new SearchableSnapshotDirectoryFactory.Options(true, false, null, null)
            );
            case SNAP_FROZEN_NOMMAP_MISS -> SearchableSnapshotDirectoryFactory.newDirectory(
                path,
                new SearchableSnapshotDirectoryFactory.Options(
                    true,
                    false,
                    ByteSizeValue.ofMb(missCacheMiB),
                    ByteSizeValue.ofKb(missRegionKiB)
                )
            );
        };
    }

    /** Reads the whole file once, so that the cache holds it before measurement starts. */
    private static void warmCache(IndexInput in) throws IOException {
        IndexInput c = in.clone();
        byte[] buf = new byte[1 << 16];
        c.seek(0);
        long remaining = c.length();
        while (remaining > 0) {
            int n = (int) Math.min(buf.length, remaining);
            c.readBytes(buf, 0, n);
            remaining -= n;
        }
    }

    private void startSampler() {
        BufferPoolMXBean direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)
            .stream()
            .filter(b -> b.getName().equals("direct"))
            .findFirst()
            .orElseThrow();
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        long start = System.nanoTime();
        System.err.println("TIER1 SAMPLE t_s rssAnon_MiB direct_count direct_used_MiB young_gcs old_gcs");
        sampler = new Thread(() -> {
            while (stopSampler == false) {
                try {
                    Thread.sleep(sampleSeconds * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                long young = 0, old = 0;
                for (GarbageCollectorMXBean g : gcs) {
                    if (g.getName().contains("Young")) {
                        young = g.getCollectionCount();
                    } else if (g.getName().contains("Old")) {
                        old = g.getCollectionCount();
                    }
                }
                System.err.printf(
                    Locale.ROOT,
                    "TIER1 SAMPLE %d %d %d %d %d %d%n",
                    (System.nanoTime() - start) / 1_000_000_000L,
                    rssAnonMiB(),
                    direct.getCount(),
                    direct.getMemoryUsed() >> 20,
                    young,
                    old
                );
            }
        }, "tier1-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    private static long rssAnonMiB() {
        try {
            for (String l : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (l.startsWith("RssAnon:")) {
                    return Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024;
                }
            }
        } catch (IOException e) {
            // not on Linux
        }
        return -1;
    }

    private static String diagnostic(String operation) throws Exception {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName("com.sun.management:type=DiagnosticCommand");
        return (String) mbs.invoke(name, operation, new Object[] { null }, new String[] { String[].class.getName() });
    }

    private static final Pattern HEAP = Pattern.compile("<heap nr=\"\\d+\"");
    private static final Pattern SYSTEM_CURRENT = Pattern.compile("<system type=\"current\" size=\"(\\d+)\"");
    private static final Pattern FREE = Pattern.compile("<total type=\"(?:fast|rest)\" count=\"\\d+\" size=\"(\\d+)\"");

    /** Summarises glibc malloc_info XML: arenas, system bytes, free bytes held in arenas. */
    static String summarize(String xml) {
        int arenas = 0;
        Matcher h = HEAP.matcher(xml);
        while (h.find()) {
            arenas++;
        }
        String tail = xml.substring(xml.lastIndexOf("</heap>") + "</heap>".length());
        long current = 0, free = 0;
        Matcher c = SYSTEM_CURRENT.matcher(tail);
        if (c.find()) {
            current = Long.parseLong(c.group(1));
        }
        Matcher f = FREE.matcher(tail);
        while (f.find()) {
            free += Long.parseLong(f.group(1));
        }
        long mib = 1L << 20;
        return String.format(
            Locale.ROOT,
            "arenas=%d system_current=%d MiB free_in_arenas=%d MiB in_use=%d MiB",
            arenas,
            current / mib,
            free / mib,
            (current - free) / mib
        );
    }

    private static byte[] compress(ZstdCompressionMode mode, byte[] data) throws IOException {
        ByteBuffersDataOutput compressedOutput = new ByteBuffersDataOutput();
        Compressor compressor = mode.newCompressor();
        compressor.compress(new ByteBuffersDataInput(List.of(ByteBuffer.wrap(data))), compressedOutput);
        compressor.close();
        return compressedOutput.toArrayCopy();
    }
}
