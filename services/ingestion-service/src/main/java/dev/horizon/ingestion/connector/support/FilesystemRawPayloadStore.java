package dev.horizon.ingestion.connector.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.horizon.ingestion.domain.port.RawPayloadStore;

/**
 * Filesystem archive of raw source responses — the development and single-node adapter.
 *
 * <p>Layout {@code {root}/{sourceId}/{yyyy}/{MM}/{dd}/{payloadHash}.{ext}} is deliberately identical
 * to the object key an S3 adapter would use, so switching storage changes the adapter and nothing
 * else. Content addressing by payload hash makes writes idempotent for free: re-fetching an
 * unchanged page overwrites the same file with the same bytes.
 *
 * <p>Failures are logged and swallowed — an archive that cannot be written must never fail a
 * collection, because the documents themselves are the product and the archive is the audit trail.
 */
public class FilesystemRawPayloadStore implements RawPayloadStore {

    private static final Logger log = LoggerFactory.getLogger(FilesystemRawPayloadStore.class);
    private static final DateTimeFormatter DATE_PATH =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    private final Path root;

    public FilesystemRawPayloadStore(Path root) {
        this.root = root;
    }

    @Override
    public Optional<String> archive(
            String sourceId, String payloadHash, Instant fetchedAt, byte[] payload, String contentType) {
        String relative =
                "%s/%s/%s.%s".formatted(sourceId, DATE_PATH.format(fetchedAt), payloadHash, extensionOf(contentType));
        try {
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root.normalize())) {
                // sourceId comes from our own configuration, but a path that escapes the root would
                // be a serious problem, so it is checked rather than assumed.
                log.warn("Refusing to archive outside the payload root: {}", relative);
                return Optional.empty();
            }
            Files.createDirectories(target.getParent());
            Files.write(target, payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return Optional.of(relative);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not archive raw payload {} for {}: {}", payloadHash, sourceId, e.toString());
            return Optional.empty();
        }
    }

    private static String extensionOf(String contentType) {
        if (contentType == null) {
            return "bin";
        }
        String lower = contentType.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("json")) {
            return "json";
        }
        if (lower.contains("xml") || lower.contains("atom") || lower.contains("rss")) {
            return "xml";
        }
        if (lower.contains("html")) {
            return "html";
        }
        return "txt";
    }
}
