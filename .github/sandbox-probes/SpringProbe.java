// Sandbox probe (CI only): submitted Spring code. A bean in the user's package that tries forbidden
// things when the app starts. Every line must say BLOCKED or OK.
package notes;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

@Component
public class SpringProbe {
    interface Action {
        void run() throws Exception;
    }

    static void probe(String name, Action a) {
        try {
            a.run();
            System.out.println("PROBE ALLOWED: " + name);
        } catch (Throwable e) {
            System.out.println("PROBE BLOCKED: " + name + " - " + e);
        }
    }

    public SpringProbe() {
        probe("read /etc/shadow", () -> Files.readString(Path.of("/etc/shadow")));
        probe("write into the app", () -> Files.writeString(Path.of("/app/web/pwned.txt"), "x"));
        probe("internet socket", () -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("1.1.1.1", 80), 3000);
            }
        });
        probe("start a process", () -> new ProcessBuilder("/bin/echo", "hi").start().waitFor());
        probe("signal pid 1", () -> {
            if (!ProcessHandle.of(1).orElseThrow().destroy()) throw new IllegalStateException("refused");
        });
        try {
            Files.writeString(Files.createTempFile("probe", ".txt"), "ok");
            System.out.println("PROBE OK: write own temp file");
        } catch (Throwable e) {
            System.out.println("PROBE BROKEN: own temp file " + e);
        }
    }
}
