// Sandbox probe (CI only): run as fully trusted code, with no security manager, under the JVM seccomp
// agent. Shows the kernel filter alone stops what trusted Spring code could be tricked into doing.
//   LAB_SANDBOX=1 java -agentpath:$LAB_JVM_SANDBOX JvmProbe.java
// Every line must say BLOCKED or OK.
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;

public class JvmProbe {
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

    public static void main(String[] args) throws Exception {
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
            Thread t = new Thread(() -> { });
            t.start();
            t.join();
            System.out.println("PROBE OK: temp file and threads still work");
        } catch (Throwable e) {
            System.out.println("PROBE BROKEN: " + e);
        }
    }
}
