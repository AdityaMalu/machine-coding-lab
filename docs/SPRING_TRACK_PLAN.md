# Plan: a hands-on Spring Boot track

Goal: learn Spring Boot by building and fixing real Spring apps in the lab, the same way the Java projects
teach concurrency: a spec, starter code, a test suite you make pass, and a reference solution. The Java
Concepts Stepper (Part 5) explains how Spring works; this track is where you write it.

Status: plan only, nothing built yet.

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
| run javac **out of process** for Spring (its own `-Xmx`) | compiling against ~40 jars in-process could push the server (`-Xmx150m`) out of memory |
| `testkit/spring`: a JUnit Platform `TestExecutionListener` that prints the existing `PASS\|` / `FAIL\|` / `RESULT\|` lines | `parseRunnerOutput` and the UI keep working unchanged; single-test runs map to JUnit method selectors |
| `lab.ps1 spring-setup` / `LabServer fetch-spring`: download the lockfile jars once, check SHA-256 | local mode and Codespaces; the Docker image does this at build time |
| Test JVM flags: `-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:MaxMetaspaceSize=160m`, AppCDS archive of the Spring jars built in the Dockerfile | startup time and memory on Render's small CPU share |
| Tests share one Spring context per project (same config, `@DirtiesContext` avoided), and mostly use MockMvc rather than a real port | a context starts once per run, not once per test class |
| `verify` + CI: every Spring solution passes, every starter fails, `sandbox-probes.sh` gets Spring probes | same safety net as the other languages |
| `codeintel.js`: add common Spring names to `BUILTINS` for `spring` (annotations, `ResponseEntity`, `JpaRepository`, `MockMvc` ...) | autocomplete before you've typed them once |

## 3. The risk: hosting (do this first)

Render free gives 512 MB for the whole container. Today: server 150 MB heap, test JVM 200 MB heap.
A Spring context adds roughly 70-120 MB of metaspace on top of heap, and starts slowly on a tiny CPU share.
The security manager is also untested with Spring (proxies, reflection, Hibernate bytecode enhancement).

**Spike (1-2 days):** one small project (project 1 below) deployed to Render, then measure:

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

## 6. Decisions needed

1. **Spring Boot version.** Pin the current 4.x release, or stay on 3.5.x, which most codebases and
   interviews still use. The topics above are the same in both; check OSS support dates at spike time.
2. **If Render fails the spike:** move Spring runs to the Oracle VM worker, or keep the track local / Codespaces only.
