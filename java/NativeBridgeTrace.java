package cubism53.mcp;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.json.simple.JSONValue;

/** A temporary UTF-8 trace with one byte offset per block of frames. */
final class NativeBridgeTrace implements AutoCloseable {
    private static final int STRIDE = 128;
    private final Path path;
    private final List<Long> offsets;
    final int count;
    private int readers;
    private boolean retired;

    private NativeBridgeTrace(Path path, List<Long> offsets, int count) {
        this.path = path;
        this.offsets = List.copyOf(offsets);
        this.count = count;
    }

    static final class Spool implements AutoCloseable {
        private final Path path = Files.createTempFile("cubism-job-trace-", ".jsonl");
        private final OutputStream stream;
        private final List<Long> offsets = new ArrayList<>();
        private long position;
        private int count;
        private boolean detached;

        Spool() throws IOException {
            try {
                stream = new BufferedOutputStream(Files.newOutputStream(path));
            } catch (IOException error) {
                Files.deleteIfExists(path);
                throw error;
            }
        }

        void append(Map<String,Object> frame) throws IOException {
            if (count % STRIDE == 0) offsets.add(position);
            byte[] line = (JSONValue.toJSONString(frame) + "\n").getBytes(StandardCharsets.UTF_8);
            stream.write(line);
            position += line.length;
            count++;
        }

        NativeBridgeTrace finish() throws IOException {
            stream.close();
            path.toFile().deleteOnExit();
            detached = true;
            return new NativeBridgeTrace(path, offsets, count);
        }

        @Override public void close() throws IOException {
            if (detached) return;
            try { stream.close(); }
            finally { Files.deleteIfExists(path); }
        }
    }

    synchronized ReadLease acquire() {
        if (retired) throw new IllegalStateException("Physics trace expired");
        readers++;
        return new ReadLease();
    }

    final class ReadLease implements AutoCloseable {
        List<Object> read(int start, int end) throws IOException {
            var frames = new ArrayList<Object>(end - start);
            if (start == end) return frames;
            int block = start / STRIDE;
            try (var channel = Files.newByteChannel(path)) {
                channel.position(offsets.get(block));
                try (var reader = new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8))) {
                    for (int frame = block * STRIDE; frame < end; frame++) {
                        String line = reader.readLine();
                        if (line == null) throw new EOFException("Incomplete physics trace");
                        if (frame >= start) frames.add(JSONValue.parse(line));
                    }
                }
            }
            return frames;
        }

        @Override public void close() {
            synchronized (NativeBridgeTrace.this) {
                if (--readers == 0 && retired) deleteLater();
            }
        }
    }

    void writeJson(Path destination, Map<String,Object> metadata, long deadline) throws IOException {
        NativeBridgeAssets.publishFile(destination, ".json", temporary -> {
            try (var writer = Files.newBufferedWriter(temporary); var lines = Files.newBufferedReader(path)) {
                String prefix = JSONValue.toJSONString(metadata);
                writer.write(prefix, 0, prefix.length() - 1);
                writer.write(",\"frames\":[");
                boolean comma = false;
                for (String line; (line = lines.readLine()) != null;) {
                    if (deadline != 0 && deadline - System.nanoTime() <= 0)
                        throw new IOException("Physics simulation timed out before publishing; retry with execution=job");
                    if (comma) writer.write(',');
                    writer.write(line);
                    comma = true;
                }
                writer.write("]}");
            }
        });
    }

    @Override public synchronized void close() {
        if (retired) return;
        retired = true;
        if (readers == 0) deleteLater();
    }

    private void deleteLater() {
        CompletableFuture.runAsync(() -> {
            try { Files.deleteIfExists(path); }
            catch (IOException error) { System.err.println("Cubism job trace cleanup: " + error); }
        });
    }
}
