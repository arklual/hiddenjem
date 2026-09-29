package dev.horizon.trends.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every declared flag must actually switch something off.
 *
 * <p>The specification warned about exactly this and the warning came true: five of the ten flags
 * guarded nothing. A unit test over {@code FeatureFlags} could not catch it — it proves the registry
 * reads configuration correctly, which stays true whether or not a controller ever consults it. The
 * failure mode is a registry of strings that looks complete, and it is discovered during the
 * demonstration where someone asks for a feature to be hidden.
 *
 * <p>So this walks the sources instead. Coarser than a request-level test, and honest about being a
 * stand-in: it answers "is this flag referenced anywhere", not "does that reference guard the right
 * thing". It catches the failure that actually happened.
 */
class FeatureFlagCoverageTest {

    private static final Path REPOSITORY =
            Path.of(System.getProperty("user.dir")).getParent().getParent();

    @Test
    void everyFlagIsUsedSomewhere() throws IOException {
        String java = sourcesUnder(REPOSITORY.resolve("services"), ".java", "/src/main/");
        String web = sourcesUnder(REPOSITORY.resolve("frontend/src"), ".tsx", null);

        var dead = new ArrayList<String>();
        for (FeatureFlag flag : FeatureFlag.values()) {
            boolean onServer = java.contains("FeatureFlag." + flag.name());
            // The client refers to a flag by its wire key, never by the enum constant.
            boolean onClient = web.contains('"' + flag.key() + '"');
            if (!onServer && !onClient) {
                dead.add(flag.key());
            }
        }

        assertThat(dead)
                .as("флаги, которые ничего не выключают — реестр обещает рычаг, которого нет")
                .isEmpty();
    }

    private static String sourcesUnder(Path root, String suffix, String pathFilter) throws IOException {
        if (!Files.isDirectory(root)) {
            return "";
        }
        try (Stream<Path> files = Files.walk(root)) {
            var text = new StringBuilder();
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(suffix))
                    .filter(path -> pathFilter == null
                            || path.toString().replace('\\', '/').contains(pathFilter))
                    .toList()) {
                text.append(Files.readString(file)).append('\n');
            }
            return text.toString();
        }
    }
}
