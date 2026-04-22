# Integration Tests Guide for Developers

This package contains full-stack integration tests for the `nexus-ingestion-api` module. The test architecture uses Testcontainers and LocalStack to simulate AWS S3 and SQS locally, enabling fast, reliable, and environment-independent test execution.

---

## Project Structure (Mandatory)

### Test Classes

```
src/test/java/org/techbd/ingest/integrationtests
```

* All integration tests must reside in this package
* Sub-packages can be created (e.g., `tcp`, `soap`)

---

### Test Resources

```
nexus-ingestion-api/src/test/resources/org/techbd/ingest/tcp-test-resources
nexus-ingestion-api/src/test/resources/org/techbd/ingest/soap-test-resources
```

* Keep protocol-specific resources and test scenarios separated (prefer protocol-based folders over per-test folders). Check existing fixtures before adding new ones.
* Examples:

  * HL7 / MLLP → tcp-test-resources
  * SOAP → soap-test-resources

---

## Architecture and Setup

### Testcontainers and LocalStack

* Uses a shared static LocalStack container
* Starts once per JVM, not per test class
* Simulates:

  * S3 (data and metadata storage)
  * SQS (message publishing)

---

### Dynamic Property Injection

* Uses `@DynamicPropertySource`
* Injects AWS endpoints and credentials at runtime
* Redirects traffic to LocalStack instead of real AWS

---

## BaseIntegrationTest (Required)

All integration tests must extend:

```
BaseIntegrationTest
```

This base class provides:

### BeforeAll

* Creates S3 buckets and SQS queues
* Uploads `list.json` (port routing configuration)

### AfterEach

* Cleans S3 and SQS state
* Ensures test isolation (no data leakage between tests)

---

## Required Annotations

All integration tests must include:

```
@NexusIntegrationTest
@Tag("integration")
```

---

## NexusIntegrationTest

`@NexusIntegrationTest` is a custom composed annotation that standardizes integration test configuration.

It includes:

* SpringBootTest (random port)
* ActiveProfiles("test")
* TestPropertySource (LocalStack configuration)
* Testcontainers

### Purpose

* Removes duplicate configuration from test classes
* Ensures consistent environment setup
* Guarantees tests run against:

  * LocalStack (S3 and SQS)
  * Test Spring profile
  * Correct port routing configuration

---

## Tag("integration")

This annotation is required for integration tests to be executed.

### Why it is required

* The Maven Failsafe plugin runs only tests tagged as "integration"
* Prevents unit tests from running in the integration test phase
* Enables clear separation between unit and integration tests

---

## Naming Convention

All integration test classes must end with:

```
*ITCase.java
```

Example:

```
NettyTcpServerITCase.java
SoapWsEndpointITCase.java
```

---

## Port Routing Configuration

```
org/techbd/ingest/portconfig/list.json
```

* Automatically uploaded during test setup
* Used by the application for runtime routing

---

## Test Execution

Run all tests (unit and integration):

```
mvn clean install
```

Run unit tests only:

```
mvn clean install -DskipITs=true
```

Run integration tests only:

```
mvn clean verify -DskipUTs=true
```

---

## Integration Test Execution Behavior

Integration tests are executed using the Maven Failsafe plugin.

### When integration tests run

* `mvn clean install`
  Runs integration tests because the verify phase is part of install

* `mvn clean install -DskipITs=true`
  Skips only integration tests

* `mvn clean install -DskipTests=true`
  Skips all tests

* `mvn failsafe:integration-test failsafe:verify`
  Runs only integration tests (commonly used in CI pipelines)

---

### Why the verify phase is required

Failsafe is designed so that:

* The integration-test phase runs the tests
* The verify phase evaluates results

The build does not fail immediately when a test fails during integration-test.

This behavior ensures:

* Cleanup steps (such as stopping LocalStack containers) still execute
* No resource leaks occur
* The build fails only after verification is complete

---

### Test Selection Rules

A test is executed only if both conditions are met:

* The class name matches `*ITCase.java`
* The test is annotated with `@Tag("integration")`

---

## Test Reports

Integration test reports are generated at:

```
nexus-ingestion-api/target/site/failsafe-report.html
```

### Notes

* Reports are generated from `target/failsafe-reports/`
* Output is HTML format for easy viewing

---

## Summary Checklist

When adding a new integration test:

* Place test under `org.techbd.ingest.integrationtests`
* Extend `BaseIntegrationTest`
* Use `@NexusIntegrationTest`
* Add `@Tag("integration")`
* Name class with `*ITCase` suffix
* Add resources to the correct folder:

  * tcp-test-resources
  * soap-test-resources
* Update `list.json` if new ports are added
---
