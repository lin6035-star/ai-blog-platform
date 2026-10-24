# Jev Test Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a test-only OpenRouter Jev adapter and a gated representative evaluation without changing the production classifier or routing.

**Architecture:** Keep the HTTP client, request protocol, response mapping, and online evaluator under `src/test`. The protocol sends one `Choice` plus independent `Noul` questions; the mapper accepts atomic goals only at probability `>= 0.70`. Production code remains unaware of Jev.

**Tech Stack:** Java 17 `HttpClient`, Jackson, JUnit 5, AssertJ, OpenRouter Decisions API.

---

### Task 1: Lock the protocol and mapping contracts

**Files:**
- Create: `blog-system/src/test/java/com/hailin/blogsystem/service/impl/JevDecisionProtocolTests.java`
- Create: `blog-system/src/test/java/com/hailin/blogsystem/service/impl/JevDecisionProtocol.java`
- Create: `blog-system/src/test/java/com/hailin/blogsystem/service/impl/JevDecisionResult.java`

- [x] Write tests asserting model `typesafe/jev-1.13`, one `primary` Choice, thirteen independent goal questions, one `needs_thinking` question, no model-owned `risk`, and the `0.70` acceptance threshold.
- [x] Run `./mvnw.cmd test "-Dtest=JevDecisionProtocolTests"` and verify compilation fails because the test-side types do not exist.
- [x] Add the minimal protocol builder and response mapper needed by the tests.
- [x] Re-run the focused test and verify it passes.

### Task 2: Add the HTTP adapter

**Files:**
- Create: `blog-system/src/test/java/com/hailin/blogsystem/service/impl/JevDecisionClientTests.java`
- Create: `blog-system/src/test/java/com/hailin/blogsystem/service/impl/JevDecisionClient.java`

- [x] Write a local `HttpServer` test that asserts the OpenRouter Bearer header and Decisions JSON body, then returns a representative composite response.
- [x] Run `./mvnw.cmd test "-Dtest=JevDecisionClientTests"` and verify compilation fails because `JevDecisionClient` does not exist.
- [x] Implement a Java 17 `HttpClient` adapter with an injectable endpoint and fail-closed handling for non-2xx or malformed responses.
- [x] Re-run the focused client test and verify it passes.

### Task 3: Add the opt-in online evaluator

**Files:**
- Create: `blog-system/src/test/resources/agent/jev-decision-evaluation-cases.json`
- Create: `blog-system/src/test/java/com/hailin/blogsystem/JevDecisionOnlineTests.java`

- [x] Add 16 representative Chinese cases covering chat, article QA, all thirteen atomic goals, and three compound requests.
- [x] Add an `@EnabledIfSystemProperty(named = "runRealJev", matches = "true")` evaluator that repeats each selected case, asserts exact accepted goals and primary route, and writes latency/token/cost data to `target/jev-decision-report.txt`.
- [x] Run the online evaluator with `JEV_API_KEY` loaded from the Windows user environment and a focused case filter; verify request/response/report plumbing before running the agreed representative subset.

### Task 4: Focused verification

- [x] Run `./mvnw.cmd test "-Dtest=JevDecisionProtocolTests,JevDecisionClientTests"`.
- [x] Run the representative online Jev evaluator only; do not run `mvn test`, `-Dgroups=eval`, or the 67-case classifier gate.
- [x] Run whitespace checks and inspect only the new Jev test-side files plus this plan.
- [x] Do not commit or push; summarize measured P50/P95, consistency, cost, and remaining production blockers.
