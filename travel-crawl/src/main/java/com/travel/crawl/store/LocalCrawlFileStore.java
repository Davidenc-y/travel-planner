package com.travel.crawl.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.travel.crawl.config.CrawlProperties;
import com.travel.crawl.model.CrawlItem;
import com.travel.crawl.util.Normalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 本地文件实现：命名 {0|1}_{yyyyMMddHHmmss}_attractions_raws.json，原子写 + 串行锁。 */
@Slf4j
@Component
public class LocalCrawlFileStore implements CrawlFileStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String PREFIX = "attractions_raws.json";

    private final Path dir;
    private final Normalizer normalizer;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object lock = new Object();

    public LocalCrawlFileStore(CrawlProperties props, Normalizer normalizer) throws IOException {
        this.dir = Path.of(props.getFileDir());
        Files.createDirectories(dir);
        this.normalizer = normalizer;
    }

    @Override
    public void append(List<CrawlItem> items) throws IOException {
        if (items == null || items.isEmpty()) {
            return;
        }
        synchronized (lock) {
            Path target = latestPending();
            List<CrawlItem> merged = new ArrayList<>();
            Map<String, CrawlItem> byKey = new LinkedHashMap<>();
            if (target != null) {
                for (CrawlItem it : readFile(target)) {
                    byKey.put(normalizer.dedupKey(it), it);
                }
            } else {
                target = dir.resolve("0_" + LocalDateTime.now().format(TS) + "_" + PREFIX);
            }
            for (CrawlItem it : items) {
                byKey.put(normalizer.dedupKey(it), it);
            }
            merged.addAll(byKey.values());
            atomicWrite(target, merged);
            log.info("[CrawlFile] 追加完成: file={}, total={}", target.getFileName(), merged.size());
        }
    }

    @Override
    public List<PendingFile> readPending() throws IOException {
        List<PendingFile> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path p : stream.filter(LocalCrawlFileStore::isPending).sorted().toList()) {
                out.add(new PendingFile(p, readFile(p)));
            }
        }
        return out;
    }

    @Override
    public void markDone(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (!name.startsWith("0_")) {
            return;
        }
        Path target = file.resolveSibling("1_" + name.substring(2));
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        log.info("[CrawlFile] 处理完成 0→1: {}", target.getFileName());
    }

    @Override
    public List<Path> listFiles() {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private Path latestPending() throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> pendings = stream.filter(LocalCrawlFileStore::isPending).sorted().toList();
            return pendings.isEmpty() ? null : pendings.get(pendings.size() - 1);
        }
    }

    private static boolean isPending(Path p) {
        return Files.isRegularFile(p) && p.getFileName().toString().startsWith("0_");
    }

    private List<CrawlItem> readFile(Path p) throws IOException {
        if (Files.size(p) == 0) {
            return List.of();
        }
        CollectionType type = mapper.getTypeFactory()
                .constructCollectionType(List.class, CrawlItem.class);
        List<CrawlItem> list = mapper.readValue(p.toFile(), type);
        return list == null ? List.of() : list;
    }

    private void atomicWrite(Path target, List<CrawlItem> items) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        mapper.writeValue(tmp.toFile(), items);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
