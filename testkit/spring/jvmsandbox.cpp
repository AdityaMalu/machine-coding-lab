// The lab's seccomp filter (testkit/cpp/labsandbox.cpp) as a JVM agent, for Spring Boot runs:
//   java -agentpath:/opt/labsandbox/libjvmsandbox.so ...   (with LAB_SANDBOX=1 in the environment)
//
// Spring was never built for Java's security manager: its jars must be fully trusted, and user code can
// get trusted code to act for it. This filter is enforced by the kernel for every thread of the JVM, so no
// Java code, trusted or not, can open internet sockets, start processes, ptrace or signal other processes.
//
// labsandbox.cpp installs the filter in a load-time constructor when LAB_SANDBOX=1. Agent_OnLoad then
// checks that it is in place and otherwise stops the JVM from starting (fail closed).
//
// Build (Linux): g++ -O2 -shared -fPIC -o libjvmsandbox.so testkit/spring/jvmsandbox.cpp
#include "../cpp/labsandbox.cpp"

#include <cstdio>
#include <sys/prctl.h>

extern "C" __attribute__((visibility("default"))) int Agent_OnLoad(void*, char*, void*) {
    if (prctl(PR_GET_SECCOMP, 0, 0, 0, 0) != 2) {  // 2 = SECCOMP_MODE_FILTER
        std::fprintf(stderr, "sandbox: seccomp filter is not installed (is LAB_SANDBOX=1 set?)\n");
        return 1;  // a non-zero result aborts JVM startup
    }
    return 0;
}
