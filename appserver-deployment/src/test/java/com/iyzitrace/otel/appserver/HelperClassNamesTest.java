package com.iyzitrace.otel.appserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.iyzitrace.otel.appserver.shared.WebAppTagger;
import org.junit.jupiter.api.Test;

/**
 * The agent injects only the listed helper classes into application class loaders. A class missing
 * from the list (e.g. a new nested class) makes the servlet advice fail silently at runtime.
 */
class HelperClassNamesTest {

  private static final String SHARED = "com.iyzitrace.otel.appserver.shared.";

  @Test
  void everySharedClassIsAnInjectedHelper() throws IOException, URISyntaxException {
    Path sharedDir =
        Path.of(WebAppTagger.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .resolve(SHARED.replace('.', '/'));
    Set<String> compiled;
    try (Stream<Path> files = Files.list(sharedDir)) {
      compiled =
          files
              .map(p -> p.getFileName().toString())
              .filter(n -> n.endsWith(".class"))
              .map(n -> SHARED + n.substring(0, n.length() - ".class".length()))
              .collect(Collectors.toCollection(TreeSet::new));
    }

    Set<String> listed = new TreeSet<>(new ServletInstrumentationModule().getAdditionalHelperClassNames());

    assertEquals(compiled, listed);
  }
}
