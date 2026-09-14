package dev.littleslot.bukkit;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps the one-test-per-job CI matrix complete as new JUnit tests are added. */
class WorkflowCoverageTest {
    @Test void everyTestMethodHasItsOwnCiJob() throws Exception {
        Path root = Paths.get("src/test/java");
        Set<String> testMethods = new HashSet<String>();
        Pattern methodPattern = Pattern.compile("@Test\\s+void\\s+(\\w+)\\s*\\(");
        List<Path> sources;
        try (Stream<Path> paths = Files.walk(root)) {
            sources = paths.filter(path -> path.toString().endsWith("Test.java"))
                    .collect(Collectors.toList());
        }
        for (Path source : sources) {
            String className = root.relativize(source).toString()
                    .replace('\\', '.').replace('/', '.').replaceAll("\\.java$", "");
            String contents = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
            Matcher matcher = methodPattern.matcher(contents);
            while (matcher.find()) testMethods.add(className + "." + matcher.group(1));
        }

        String workflow = new String(Files.readAllBytes(Paths.get(".github/workflows/preview.yml")),
                StandardCharsets.UTF_8);
        Map<?, ?> document = new Yaml().load(workflow);
        Map<?, ?> jobs = (Map<?, ?>) document.get("jobs");
        Map<?, ?> testJob = (Map<?, ?>) jobs.get("test");
        Map<?, ?> strategy = (Map<?, ?>) testJob.get("strategy");
        Map<?, ?> matrix = (Map<?, ?>) strategy.get("matrix");
        List<?> cases = (List<?>) matrix.get("case");
        Set<String> selectors = new HashSet<String>();
        for (Object entry : cases) {
            String selector = String.valueOf(((Map<?, ?>) entry).get("selector"));
            assertTrue(selectors.add(selector), "Duplicate CI test selector: " + selector);
        }
        assertEquals(testMethods, selectors, "The CI matrix must list every JUnit test method exactly once");
    }
}
