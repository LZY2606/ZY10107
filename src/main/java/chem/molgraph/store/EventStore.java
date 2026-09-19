package chem.molgraph.store;

import chem.molgraph.engine.Canonical;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Append-only event log. Each frame is one JSON object with a payload hash and a
 * hash chain (prevHash). Evidence (imported structures) therefore can never be
 * rewritten in place: any correction arrives as a new event.
 *
 * Recovery behavior on startup: the log is verified end-to-end. If a frame is
 * corrupt or the chain breaks, the good prefix is quarantined into data/quarantine,
 * replay stops at the last verified frame and the store becomes read-only until the
 * operator resolves the file (see README "故障恢复").
 */
@Component
public class EventStore {

    private final ObjectMapper mapper;
    private final Path logFile;
    private final Path quarantineDir;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<Event> events = new ArrayList<>();
    private long nextSeq = 0;
    private String lastHash = "GENESIS";
    private boolean readOnly;
    private String recoveryError;
    private long quarantinedFrames;

    public EventStore(ObjectMapper mapper, @Value("${molgraph.data.dir:data}") String dataDir) {
        this.mapper = mapper;
        Path dir = Path.of(dataDir);
        this.logFile = dir.resolve("events.log");
        this.quarantineDir = dir.resolve("quarantine");
    }

    @PostConstruct
    public synchronized void init() {
        lock.writeLock().lock();
        try {
            Files.createDirectories(logFile.getParent());
            if (!Files.exists(logFile)) {
                return;
            }
            recover();
        } catch (IOException e) {
            throw new EventStoreException("无法初始化事件日志: " + logFile, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void recover() throws IOException {
        List<String> goodLines = new ArrayList<>();
        long expectedSeq = 0;
        String expectedHash = "GENESIS";
        long badLine = -1;
        String reason = null;

        try (BufferedReader reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            String line;
            long lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                Event candidate;
                try {
                    JsonNode node = mapper.readTree(line);
                    long seq = node.path("seq").asLong(-1);
                    String type = node.path("type").asText(null);
                    String payload = node.path("payload").asText(null);
                    String payloadHash = node.path("payloadHash").asText(null);
                    String prev = node.path("prevHash").asText(null);
                    long timestamp = node.path("timestamp").asLong(0);
                    String self = node.path("chainHash").asText(null);
                    if (seq != expectedSeq || type == null || payload == null || payloadHash == null || prev == null) {
                        badLine = lineNo;
                        reason = "帧字段缺失或序号不连续";
                        break;
                    }
                    if (!Canonical.sha256(payload).equals(payloadHash)) {
                        badLine = lineNo;
                        reason = "载荷哈希不匹配（日志可能被原地改写）";
                        break;
                    }
                    if (!expectedHash.equals(prev)) {
                        badLine = lineNo;
                        reason = "哈希链断裂";
                        break;
                    }
                    String recomputed = Canonical.sha256(prev + "|" + seq + "|" + type + "|" + payloadHash);
                    if (!recomputed.equals(self)) {
                        badLine = lineNo;
                        reason = "帧链哈希不匹配";
                        break;
                    }
                    candidate = new Event(seq, type, payload, payloadHash, prev, timestamp);
                } catch (Exception e) {
                    badLine = lineNo;
                    reason = "帧无法解析: " + e.getMessage();
                    break;
                }
                goodLines.add(line);
                events.add(candidate);
                expectedHash = candidate.chainHash();
                expectedSeq++;
            }
        }

        if (badLine >= 0) {
            Files.createDirectories(quarantineDir);
            Path backup = quarantineDir.resolve("events-" + System.currentTimeMillis() + ".corrupt.log");
            Files.copy(logFile, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.write(logFile, goodLines, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
            readOnly = true;
            recoveryError = "第 " + badLine + " 行: " + reason
                    + "。已将完整日志隔离到 " + backup.toAbsolutePath()
                    + "，仅保留此前 " + goodLines.size() + " 个有效事件。";
            quarantinedFrames = badLine - goodLines.size();
        }
        nextSeq = expectedSeq;
        lastHash = expectedHash;
    }

    public Event append(String type, Object payload) {
        lock.writeLock().lock();
        try {
            if (readOnly) {
                throw new EventStoreException("事件日志处于只读恢复模式: " + recoveryError);
            }
            String json;
            try {
                json = mapper.writeValueAsString(payload);
            } catch (JsonProcessingException e) {
                throw new EventStoreException("事件序列化失败", e);
            }
            long seq = nextSeq;
            String payloadHash = Canonical.sha256(json);
            Event event = new Event(seq, type, json, payloadHash, lastHash, System.currentTimeMillis());
            String frame;
            try {
                frame = mapper.writeValueAsString(new Frame(seq, type, json, payloadHash,
                        lastHash, event.chainHash(), event.timestamp()));
            } catch (JsonProcessingException e) {
                throw new EventStoreException("帧序列化失败", e);
            }
            try {
                Files.writeString(logFile, frame + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new EventStoreException("追加事件失败（未提交到内存投影）", e);
            }
            events.add(event);
            lastHash = event.chainHash();
            nextSeq++;
            return event;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Event> events() {
        lock.readLock().lock();
        try {
            return List.copyOf(events);
        } finally {
            lock.readLock().unlock();
        }
    }

    public long currentVersion() {
        lock.readLock().lock();
        try {
            return nextSeq;
        } finally {
            lock.readLock().unlock();
        }
    }

    public String headHash() {
        lock.readLock().lock();
        try {
            return lastHash;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isReadOnly() {
        lock.readLock().lock();
        try {
            return readOnly;
        } finally {
            lock.readLock().unlock();
        }
    }

    public String recoveryError() {
        return recoveryError;
    }

    public long quarantinedFrames() {
        return quarantinedFrames;
    }

    private record Frame(long seq, String type, String payload, String payloadHash,
                         String prevHash, String chainHash, long timestamp) {
    }
}
