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

## Base Class Annotations

The `BaseIntegrationTest` class is annotated with:

```
@NexusIntegrationTest
```

By extending `BaseIntegrationTest`, your test class automatically inherits this configuration.

---

## NexusIntegrationTest

`@NexusIntegrationTest` is a custom composed annotation that standardizes integration test configuration.

It includes:

* SpringBootTest (random port)
* ActiveProfiles("test")
* TestPropertySource (LocalStack configuration)
* Testcontainers
* Tag("integration")

### Purpose

* Removes duplicate configuration from test classes
* Ensures consistent environment setup
* Guarantees tests run against:

  * LocalStack (S3 and SQS)
  * Test Spring profile
  * Correct port routing configuration

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
* The test extends `BaseIntegrationTest` (which provides the required `@NexusIntegrationTest` annotation and "integration" tag)

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

## Asserting Flows based on list.json

The `IngestionAssertionHelper` is the standard utility for asserting S3 and SQS outcomes in integration tests. The method you choose and the parameters you provide depend directly on the port configuration defined in `list.json`.

### 1. Default Flow (Standard Routing)

When your `list.json` has standard routing without `route=/hold` and no specific `sourceId` / `msgType` for tenant segregation:

**list.json excerpt:**
```json
{
  "port": 9000,
  "route": "/api/ingest"
}
```

**Assertion:**
```java
assertionHelper().assertDefaultFlow(FlowAssertionParams.builder()
    .dataBucket(dataBucketName)
    .metadataBucket(metadataBucketName)
    .queueUrl(mainQueueUrl)
    .expectedMessageGroupId("pass expected messagegrroupid")
    .expectedPayload(requestPayload)
    .build(), softly);
```

### 2. Hold Flow (Single Bucket, Port-based)

When your `list.json` specifies `route=/hold`, the application stores both data and metadata in a single "hold" bucket, categorized by port.

**list.json excerpt:**
```json
{
  "port": 2575,
  "route": "/hold",
  "dataDir": "/outbound",
  "metadataDir": "/outbound"
}
```

**Assertion:**
```java
assertionHelper().assertHoldFlow(FlowAssertionParams.builder()
    .dataBucket(holdBucketName)
    .holdFlow(true)
    .port(2575)
    .dataDir("/outbound")
    .metadataDir("/outbound")
    .queueUrl(holdQueueUrl)
    .expectedMessageGroupId("pass expected messagegrroupid")
    .expectedPayload(requestPayload)
    .build(), softly);
```

### 3. Tenant-Aware Flow (Source & Message Type)

When `list.json` includes `sourceId` and `msgType`, the application injects a tenant segment into the S3 key path.

**list.json excerpt:**
```json
{
  "sourceId": "SYS_A",
  "msgType": "ADT",
  "dataDir": "/tenant-data"
}
```

**Assertion:**
```java
assertionHelper().assertCustomFlow(FlowAssertionParams.builder()
    .dataBucket(dataBucketName)
    .metadataBucket(metadataBucketName)
    .tenantId("SYS_A_ADT") // sourceId + "_" + msgType
    .dataDir("/tenant-data")
    .queueUrl(mainQueueUrl)
    .expectedPayload(requestPayload)
    .build(), softly);
```

### 4. Error Flow

When an ingestion fails (e.g., parsing error), the payload is stored under an `error/` prefix, and SQS messaging is skipped.

**Assertion:**
```java
assertionHelper().assertErrorFlow(FlowAssertionParams.builder()
    .dataBucket(dataBucketName)
    .errorFlow(true)
    .expectedPayload(requestPayload)
    // No queueUrl needed since SQS is skipped
    .build(), softly);
```

---

## Summary Checklist

When adding a new integration test:

* Place test under `org.techbd.ingest.integrationtests`
* Extend `BaseIntegrationTest`
* Name class with `*ITCase` suffix
* Use `IngestionAssertionHelper` to assert S3 and SQS flows
* Add resources to the correct folder:

  * tcp-test-resources
  * soap-test-resources
* Update `list.json` if new ports are added
---
