package testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

/**
 * Runs JUnit 5 tests for the Spring Boot projects and prints the same lines as testkit.TestRunner,
 * so the lab server parses both the same way. Each argument is a class name or {@code Class#method}.
 *
 *   PASS|Class|method|name|millis
 *   FAIL|Class|method|name|millis|message
 *   RESULT|passed|failed|total
 */
public final class SpringRunner {

    public static void main(String[] args) {
        List<DiscoverySelector> selectors = new ArrayList<>();
        for (String arg : args) {
            int hash = arg.indexOf('#');
            selectors.add(hash < 0 ? selectClass(arg) : selectMethod(arg.substring(0, hash), arg.substring(hash + 1)));
        }
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectors)
                .configurationParameters(Map.of(
                        "junit.jupiter.execution.timeout.testable.method.default", "20 s",
                        "junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD"))
                .build();

        AtomicInteger passed = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        Map<String, Long> started = new java.util.concurrent.ConcurrentHashMap<>();
        Launcher launcher = LauncherFactory.create();
        launcher.execute(request, new TestExecutionListener() {
            @Override
            public void executionStarted(TestIdentifier id) {
                if (id.isTest()) started.put(id.getUniqueId(), System.nanoTime());
            }

            @Override
            public void executionSkipped(TestIdentifier id, String reason) {
                if (id.isTest()) report(id, 0, "Skipped: " + reason);
            }

            @Override
            public void executionFinished(TestIdentifier id, TestExecutionResult result) {
                if (!id.isTest()) {
                    // a failing class-level step (e.g. the context won't start) fails every test in it
                    if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
                        System.out.println("ERROR|" + id.getDisplayName() + ": " + message(result.getThrowable().orElse(null)));
                    }
                    return;
                }
                long t0 = started.getOrDefault(id.getUniqueId(), System.nanoTime());
                long millis = (System.nanoTime() - t0) / 1_000_000;
                String error = result.getStatus() == TestExecutionResult.Status.SUCCESSFUL ? null
                        : message(result.getThrowable().orElse(null));
                report(id, millis, error);
            }

            private void report(TestIdentifier id, long millis, String error) {
                String cls = "?";
                String method = id.getDisplayName();
                if (id.getSource().orElse(null) instanceof MethodSource m) {
                    cls = m.getClassName().substring(m.getClassName().lastIndexOf('.') + 1);
                    method = m.getMethodName();
                }
                String name = id.getDisplayName().replaceAll("\\(\\)$", "").replace('|', '/');
                String prefix = cls + "|" + method + "|" + name + "|";
                if (error == null) {
                    passed.incrementAndGet();
                    System.out.println("PASS|" + prefix + millis);
                } else {
                    failed.incrementAndGet();
                    System.out.println("FAIL|" + prefix + millis + "|" + error.replace('\n', ' ').replace('\r', ' '));
                }
                System.out.flush();
            }
        });
        System.out.println("RESULT|" + passed.get() + "|" + failed.get() + "|" + (passed.get() + failed.get()));
        System.out.println(stats());
        System.out.flush();
        // leaked non-daemon threads (executors, schedulers) must not keep the JVM alive
        System.exit(failed.get() == 0 ? 0 : 1);
    }

    /** One line of resource use, shown in the run output: JVM time, peak memory (Linux adds RSS and cgroup). */
    static String stats() {
        StringBuilder sb = new StringBuilder("Run stats: ");
        sb.append("jvm ").append(java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime()).append(" ms");
        long heap = 0, meta = 0;
        for (java.lang.management.MemoryPoolMXBean pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            long peak = pool.getPeakUsage() == null ? 0 : pool.getPeakUsage().getUsed();
            if (pool.getType() == java.lang.management.MemoryType.HEAP) heap += peak;
            else if (pool.getName().equals("Metaspace")) meta = peak;
        }
        sb.append(", heap peak ").append(heap >> 20).append(" MB, metaspace ").append(meta >> 20).append(" MB");
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/self/status"))) {
                if (line.startsWith("VmHWM:")) sb.append(", RSS peak ").append(Long.parseLong(line.replaceAll("\\D", "")) >> 10).append(" MB");
            }
            java.nio.file.Path cg = java.nio.file.Path.of("/sys/fs/cgroup/memory.peak");
            if (java.nio.file.Files.isReadable(cg)) {
                sb.append(", container peak ").append(Long.parseLong(java.nio.file.Files.readString(cg).strip()) >> 20).append(" MB");
            }
        } catch (Exception | Error e) {
            // not Linux, or not readable: the JVM numbers are enough
        }
        return sb.toString();
    }

    /** The assertion message, plus the root cause when Spring wraps it (e.g. "Failed to load ApplicationContext"). */
    static String message(Throwable t) {
        if (t == null) return "failed";
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = t.getMessage() == null ? "" : t.getMessage();
        String out;
        if (msg.startsWith("ApplicationContext failure threshold")) {
            out = "The app failed to start (see the first failing test, and APPLICATION FAILED TO START in the output)";
        } else if (msg.startsWith("Failed to load ApplicationContext")) {
            // the context description in the message is long and says nothing useful; the cause does
            out = "The app failed to start: " + describe(root);
        } else {
            out = describe(t);
            if (root != t) out += " | caused by " + describe(root);
        }
        return out.length() > 2000 ? out.substring(0, 2000) + "..." : out;
    }

    static String describe(Throwable t) {
        String msg = t.getMessage();
        String type = t.getClass().getSimpleName();
        if (t instanceof AssertionError) return msg == null ? type : msg.strip();
        return msg == null ? type : type + ": " + msg.strip();
    }
}
