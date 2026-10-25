# Plan: a hands-on Spring Boot track

Goal: learn Spring Boot by building and fixing real Spring apps in the lab, the same way the Java projects
teach concurrency: a spec, starter code, a test suite you make pass, and a reference solution. The Java
Concepts Stepper (Part 5) explains how Spring works; this track is where you write it.

Status (2026-10-25): **phase 0 spike done, all three gates passed on Render** (section 3). Spring Boot 4.1.2.
The Spring Boot language and the Notes API project are live but hidden: open the lab with `?preview=spring`.
On the hosted site only the reference solution runs until `LAB_SPRING_USER_CODE=1` is set.

## 1. How it fits the lab

**Spring Boot becomes a fifth entry in the language picker** (`java | python | go | cpp | spring`). That reuses
everything the lab already has per language: separate workspace, progress, Reset, hub progress messages.

```
projects/<id>/spring/README.md
projects/<id>/spring/starter/src/main/java/...     compiles, app starts, endpoints are TODO
projects/<id>/spring/starter/src/main/resources/application.properties
projects/<id>/spring/tests/src/...                 JUnit 5 + Spring Boot Test (read-only, part of the spec)
projects/<id>/spring/solution/src/...
```

Spring-only projects get `"langs": ["spring"]` in `catalog.json`, so they appear only when Spring Boot
is picked. Existing projects can also get a `spring/` port (see project 9).

**Still no build tools for the learner.** Maven is not used at run time. The lab ships a lockfile of
pinned jars (`testkit/spring/libs.lock`: Maven Central URL + SHA-256 per jar). The server compiles with
`javac` against those jars and runs the tests on the JUnit Platform, just like it runs the Java projects today.

## 2. Runner changes (server/LabServer.java and testkit)

| Change | Why |
|---|---|
| `LANGS` += `"spring"`, display name "Spring Boot" | picker, workspace, progress |
| `runSpring()`: compile user `src/main/java` then tests, classpath = libs + kit + tests + user, copy `src/main/resources` | same three-root layout as `runJava`, so the security policy still tells them apart |
| compile with **`-parameters`** | Spring 6.1+ needs it for `@PathVariable` / `@RequestParam` without explicit names; without it the learner gets confusing errors |
| ~~run javac out of process~~ not needed | the spike measured in-process javac against 79 jars well within the server's memory |
| `testkit/spring`: a JUnit Platform `TestExecutionListener` that prints the existing `PASS\|` / `FAIL\|` / `RESULT\|` lines | `parseRunnerOutput` and the UI keep working unchanged; single-test runs map to JUnit method selectors |
| `lab.ps1 spring-setup` / `LabServer fetch-spring`: download the lockfile jars once, check SHA-256 | local mode and Codespaces; the Docker image does this at build time |
| Test JVM flags: `-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:MaxMetaspaceSize=160m`, AppCDS archive of the Spring jars built in the Dockerfile | startup time and memory on Render's small CPU share |
| Tests share one Spring context per project (same config, `@DirtiesContext` avoided), and mostly use MockMvc rather than a real port | a context starts once per run, not once per test class |
| `verify` + CI: every Spring solution passes, every starter fails, `sandbox-probes.sh` gets Spring probes | same safety net as the other languages |
| `codeintel.js`: add common Spring names to `BUILTINS` for `spring` (annotations, `ResponseEntity`, `JpaRepository`, `MockMvc` ...) | autocomplete before you've typed them once |

## 3. The risk: hosting (spike results)

### Results (2026-10-25, Notes API reference solution, 19 tests)

| Gate | Target | Live Render (8 runs) | CI container at 512 MB / 0.1 CPU | Verdict |
|---|---|---|---|---|
| Full run time | < 60 s | **33-42 s** (compile 5.6-6.5 s, JVM + context + tests 26-28 s) | 32-34 s warm, 61 s first run | pass |
| Memory | fits 512 MB | **container peak ~290 MB**, test JVM RSS ~138 MB | peak 248-272 MB, no OOM kill | pass |
| Sandbox | probes blocked | solution runs with the seccomp agent loaded (it fails closed) | all probes blocked, trusted and submitted code | pass, with a change (below) |

Locally (fast PC) a run takes about 5 s. For comparison, a Java project run on Render takes about 6 s.

**What it took:**
- **CDS archive** of the Spring jars and the kit, built in the Dockerfile: JVM time down about 40% (warm
  runs went from 57-62 s to 32-34 s under the CI limits), metaspace from 41 MB to 3 MB.
- **Compiler warm-up** at server start, so the first visitor's compile is about 6 s, not 35-47 s.
- Mockito switched to its subclass mock maker and reflection member accessor (the defaults attach a Java
  agent at startup), and quiet logging via a `logback-test.xml` in the kit.

**Security finding (important).** Spring can't run under a security manager strict enough to contain
submitted code. Its CGLIB proxies (for `@Configuration`, `@Transactional`, `@Async`, `@Cacheable`) are defined in
the user's package, so they get the user's permissions, and Spring 6+ has no `doPrivileged` blocks. User code
needs `getProtectionDomain` and `suppressAccessChecks`, and all 79 jars need full trust, which lets user code get
trusted Spring code to act for it. So the security manager is only a second layer for Spring. The first is
the C++ track's **seccomp filter loaded into the JVM as an agent** (`testkit/spring/jvmsandbox.cpp`): the kernel
blocks internet sockets, new processes, ptrace and signals for every thread, trusted or not. CI proves it
both ways: `JvmProbe.java` (trusted code, no security manager) and `SpringProbe.java` (submitted code).
File reads still rely on the `runner` user's permissions, the same as the C++ track.

**Next decision:** set `LAB_SPRING_USER_CODE=1` in the Render dashboard to let visitors run their own Spring
code. CI's opt-in server already runs submitted code with every probe blocked.

### The original gates

| Gate | Pass if |
|---|---|
| Full run (compile + context + tests), p95 | < 60 s (run timeout is 90 s) |
| Peak memory of server + test JVM | fits in 512 MB without the OOM killer |
| Security manager | context starts, user code still can't open files, sockets or processes (probes fail as expected) |

**If a gate fails, fallbacks in order:**
1. Tune: CDS, smaller heap, `spring.main.lazy-initialization=true` in tests, drop Tomcat (MockMvc only).
2. Run Spring jobs on the Oracle VM worker (gVisor gives isolation even without the security manager);
   [SCALING_PLAN.md](SCALING_PLAN.md) phase 2 already describes it.
3. Spring track in local mode and Codespaces only, hidden on the hosted site (the picker already hides
   languages that aren't available).

Never drop the security manager on Render without gVisor or an equivalent: user code would then run with no
network or file restrictions.

## 4. Projects

Each one links to the stepper topic that explains it, and the stepper topic links back ("Practice this").
Mix of build and debug, like the Java projects. Concurrency shows up again in 5 and 6.

| # | Project | Type | Level | What you implement / fix | Stepper |
|---|---|---|---|---|---|
| 1 | **Notes API** | build | Easy | `@RestController`, a service layer with constructor injection, `@Valid` request bodies, `@RestControllerAdvice` returning `ProblemDetail`, correct status codes (201 + Location, 404, 409, 422) | 5.6 |
| 2 | **App Won't Start** | debug | Easy | two beans of one type (`@Primary` / `@Qualifier`), a circular dependency, a prototype bean injected into a singleton, `@ConfigurationProperties` binding and validation, a profile-specific bean | 5.2, 3.7b |
| 3 | **Orders with JPA** | build | Medium | entities and relations, Spring Data repositories, derived and `@Query` queries, paging and sorting, fix an N+1 with a fetch join or `@EntityGraph` (tests count SQL statements via Hibernate statistics), H2 in-memory | 5.11 |
| 4 | **Checkout Incident** | debug | Medium | `@Transactional` self-invocation that skips the proxy, a checked exception that doesn't roll back, an audit log that needs `REQUIRES_NEW`, a slow external call inside a transaction holding a connection (pool of 2 in tests) | 5.4, 6.4 |
| 5 | **Flash Sale Inventory** | build | Hard | no overselling when 50 requests race: `@Version` optimistic locking + retry, then a pessimistic `SELECT ... FOR UPDATE` variant, idempotency keys enforced by a unique constraint; tests fire parallel requests with an `ExecutorService` | 4.x, 8.1 |
| 6 | **Async Report Service** | build | Medium | `@Async` on a bounded `ThreadPoolTaskExecutor` with a rejection policy, `CompletableFuture` fan-out with timeouts, MDC / request context carried across threads with a `TaskDecorator`, a `@Scheduled` job that never overlaps, virtual threads switched on with one property | 5.10, 4.12-4.14, 4.17, 6.3 |
| 7 | **Cache That Lies** | debug | Medium | `@Cacheable` key collisions, caching through self-invocation, missing `@CacheEvict` on update, a stampede on a cold key (`sync = true`), retries that retry non-retryable errors | 5.10 |
| 8 | **Secured Workspace API** | build | Hard | a stateless `SecurityFilterChain`, JWT issue and verify (HMAC key, resource server), `@PreAuthorize` roles, ownership checks so user A can't read user B's data (IDOR), 401 vs 403 | 5.9 |
| 9 | **Retry-Safe Issue Service, Spring edition** | build | Hard | a Spring port of `issue-creation-api` from the same spec: idempotent creates, `ETag` / `If-Match` optimistic locking, cursor pagination; tests use `@WebMvcTest` and `@DataJpaTest` slices | 5.12 |

Pinned stack (one version for all projects): Spring Boot starters web, validation, data-jpa, security,
oauth2-resource-server (8 only), cache; H2; spring-boot-test + AssertJ + MockMvc; JUnit 5.

## 5. Phases

| Phase | Work | Done when |
|---|---|---|
| 0. Spike | project 1 minimal, runner hacked in, deployed to Render, the three gates measured | go / no-go decided, numbers written into this doc |
| 1. Runner | section 2 in full: lockfile + fetch, `runSpring`, JUnit listener, Dockerfile, CI verify + probes, picker, code intel | `lab.ps1 verify` passes locally and in CI; a hosted run shows per-test results |
| 2. First projects | 1, 2, 3 (spec, starter, tests, solution) | each solution passes, each starter fails for the right reason |
| 3. Core projects | 4, 5, 6 | same, and project 5's tests fail reliably on a racy solution (run 20 times) |
| 4. Finish | 7, 8, 9; README table and project count; stepper Part 5 "Practice this" links; hub progress counts Spring | the track is visible on the hosted site (or in local mode if the spike said so) |

## 6. Decisions

1. **Spring Boot version: 4.1.2** (decided at the spike; 3.5's open-source support has ended). Boot 4 notes:
   test annotations moved to per-module packages (`org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`),
   Jackson 3 (`tools.jackson`), JUnit 6. Tests send JSON as strings, so they don't depend on the Jackson API.
2. **Render passed the spike**, so Spring stays on the main site; the Oracle worker is not needed for it.
3. **Open:** turn on submitted Spring code on Render (`LAB_SPRING_USER_CODE=1`).

## 7. Updating the Spring jars

1. Write a throwaway `pom.xml` with `spring-boot-starter-parent` at the new version and the starters the
   projects use (today: `webmvc`, `validation`, `webmvc-test`, plus `junit-platform-launcher`).
2. `mvn dependency:copy-dependencies -DincludeScope=test` and `mvn dependency:list -DincludeScope=test`.
3. Rebuild `testkit/spring/libs.lock`: one `groupId:artifactId:version sha256` line per jar.
4. `java server/LabServer.java fetch-spring`, then run every Spring solution and starter (CI does too).
   Maven is only needed for step 2, never by the lab or its users.
