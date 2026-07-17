package io.github.pimak.logbackntfy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Lint gate: this library must be configured exclusively via JavaBean setters, never by reading
 * the process environment, and must ship no Logback XML of its own — it is *configured by* the
 * consumer's Joran config, never contains any (env-var names/substitution and XML wiring both
 * belong to the consumer).
 */
class NoGetenvOrXmlGuardTest {

  private static final Pattern GETENV_CALL = Pattern.compile("System\\.getenv\\(");

  @Test
  void noGetenvCallInProductionCode() throws IOException {
    Path srcMain = Path.of("").toAbsolutePath().resolve("src/main/java");
    assertThat(srcMain).as("src/main/java must exist").isDirectory();

    List<String> violations = new ArrayList<>();
    try (Stream<Path> javaFiles = Files.walk(srcMain)) {
      javaFiles
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(
              path -> {
                try {
                  List<String> lines = Files.readAllLines(path);
                  for (int i = 0; i < lines.size(); i++) {
                    Matcher matcher = GETENV_CALL.matcher(lines.get(i));
                    if (matcher.find()) {
                      violations.add(path.toAbsolutePath() + ":" + (i + 1));
                    }
                  }
                } catch (IOException e) {
                  throw new RuntimeException("Failed to read " + path, e);
                }
              });
    }

    assertThat(violations).as("no System.getenv() allowed in logback-ntfy").isEmpty();
  }

  @Test
  void noXmlShippedInMainResources() throws IOException {
    Path resourcesDir = Path.of("").toAbsolutePath().resolve("src/main/resources");
    if (!Files.isDirectory(resourcesDir)) {
      // No resources directory at all is a valid pass — this module ships no XML of its own.
      return;
    }

    try (Stream<Path> files = Files.walk(resourcesDir)) {
      List<String> xmlFiles =
          files.filter(p -> p.toString().endsWith(".xml")).map(Path::toString).toList();
      assertThat(xmlFiles).as("logback-ntfy ships no XML of its own").isEmpty();
    }
  }
}
