package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.ReactiveQhorusMcpTools;
import io.quarkiverse.mcp.server.Tool;

/**
 * Verifies that no public non-@Tool method shares a name with a @Tool-annotated method
 * in either MCP tools class. Such overloads confuse quarkus-mcp-server's Jandex scanner,
 * silently dropping the @Tool method from the registered tool list. See issue #129.
 */
class ToolOverloadDiscoverabilityTest {

    @Test
    void qhorusMcpTools_noPublicNonToolOverloadsOfToolMethods() {
        assertNoPublicNonToolOverloads(QhorusMcpTools.class);
    }

    @Test
    void reactiveQhorusMcpTools_noPublicNonToolOverloadsOfToolMethods() {
        assertNoPublicNonToolOverloads(ReactiveQhorusMcpTools.class);
    }

    private void assertNoPublicNonToolOverloads(Class<?> clazz) {
        Method[] methods = clazz.getDeclaredMethods();

        Set<String> toolMethodNames = Arrays.stream(methods)
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .map(Method::getName)
                .collect(Collectors.toSet());

        List<String> violations = Arrays.stream(methods)
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> !m.isAnnotationPresent(Tool.class))
                .filter(m -> toolMethodNames.contains(m.getName()))
                .map(m -> clazz.getSimpleName() + "#" + m.getName()
                        + "(" + Arrays.stream(m.getParameterTypes())
                                .map(Class::getSimpleName)
                                .collect(Collectors.joining(", ")) + ")")
                .sorted()
                .toList();

        if (!violations.isEmpty()) {
            fail("Public non-@Tool overloads shadow @Tool method discovery (Refs #129):\n  "
                    + String.join("\n  ", violations));
        }
    }
}
