package org.techbd.ingest.integrationtests.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import org.assertj.core.api.SoftAssertions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * Generic, reusable S3 + SQS assertion helper for all integration test flows.
 *
 * <p>Covers every combination described in {@code list.json}:
 * <ul>
 *   <li><b>Default flow</b> – data in {@code DEFAULT_DATA_BUCKET}, metadata in
 *       {@code DEFAULT_METADATA_BUCKET}, key prefix {@code data/YYYY/MM/DD/…}</li>
 *   <li><b>Hold flow</b> – data <em>and</em> metadata both in {@code HOLD_BUCKET},
 *       key prefixes driven by {@code dataDir}/{@code metadataDir} and optional
 *       {@code port} from the port-config entry.</li>
 *   <li><b>Error flow</b> – payload stored under {@code error/YYYY/MM/DD/…}; SQS
 *       assertion is skipped.</li>
 *   <li><b>Tenant-aware flow</b> – when {@code sourceId} / {@code msgType} are
 *       present in the port-config entry the tenant segment is injected into the
 *       S3 key path.</li>
 * </ul>
 *
 * <h3>Entry point methods</h3>
 * <ul>
 *   <li>{@link #assertDefaultFlow} – default two-bucket flow (no route / hold)</li>
 *   <li>{@link #assertHoldFlow} – single-bucket hold flow</li>
 *   <li>{@link #assertErrorFlow} – error bucket flow (no SQS check)</li>
 *   <li>{@link #assertCustomFlow} – fully parameterised; used for any list.json
 *       combination not covered by the convenience methods above.</li>
 * </ul>
 *
 * <h3>Path derivation from metadata JSON</h3>
 * Instead of re-computing expected keys from scratch, the helper reads the
 * metadata object that the application writes to S3, extracts
 * {@code interactionId} and {@code timestamp} from it, and then verifies that
 * the actual S3 keys follow the expected naming convention.  This approach is
 * robust against small timestamp-format changes in the application code.
 */
public class IngestionAssertionHelper {

    // ── Constants ──────────────────────────────────────────────────────────────

    /** Prefix used by the default data bucket. */
    public static final String PREFIX_DATA = "data";
    /** Prefix used by the error bucket path. */
    public static final String PREFIX_ERROR = "error";
    /** Sub-path separator used in hold keys. */
    public static final String HOLD_SEGMENT = "hold";
    /** Sub-path for metadata inside the hold bucket. */
    public static final String HOLD_META_SEGMENT = "hold/metadata";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Infrastructure references (supplied at construction time) ──────────────

    private final S3Client s3Client;
    private final SqsClient sqsClient;

    public IngestionAssertionHelper(S3Client s3Client, SqsClient sqsClient) {
        this.s3Client = s3Client;
        this.sqsClient = sqsClient;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Public entry-point methods
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Validates the <em>default</em> two-bucket flow (no route or hold).
     *
     * <p>Equivalent to the port-9000 SOAP or port-2575 TCP happy-path:
     * <pre>
     * Data:     DEFAULT_DATA_BUCKET   /  data/YYYY/MM/DD/{interactionId}_{ts}
     * Metadata: DEFAULT_METADATA_BUCKET / metadata/YYYY/MM/DD/{interactionId}_{ts}_metadata.json
     * ACK:      DEFAULT_DATA_BUCKET   /  data/YYYY/MM/DD/{interactionId}_{ts}_ack
     * </pre>
     *
     * @param params      assertion parameters (see {@link FlowAssertionParams})
     * @param softly      soft-assertion collector
     */
    public void assertDefaultFlow(FlowAssertionParams params, SoftAssertions softly) throws Exception {
        assertCustomFlow(params, softly);
    }

    /**
     * Validates the <em>hold</em> single-bucket flow.
     *
     * <p>Applies when {@code route=/hold} is set in the port-config entry.
     * Both data and metadata land in the same bucket (HOLD_BUCKET).
     *
     * <p>Key structure (port-based, no tenant):
     * <pre>
     * Data:     HOLD_BUCKET / {dataDir}/hold/{port}/YYYY/MM/DD/{ts}_{fileName}
     * Metadata: HOLD_BUCKET / {metadataDir}/hold/metadata/{port}/YYYY/MM/DD/{ts}_{fileName}_metadata.json
     * ACK:      HOLD_BUCKET / {dataDir}/hold/{port}/YYYY/MM/DD/{ts}_{fileName}_ack
     * </pre>
     *
     * @param params      assertion parameters
     * @param softly      soft-assertion collector
     */
    public void assertHoldFlow(FlowAssertionParams params, SoftAssertions softly) throws Exception {
        assertCustomFlow(params, softly);
    }

    /**
     * Validates the <em>error</em> flow.
     *
     * <p>When ingestion fails the application stores the payload under the
     * {@code error/} prefix (no dataDir applied). SQS assertion is skipped.
     *
     * <pre>
     * Data: dataBucket / error/YYYY/MM/DD/{interactionId}_{ts}
     * </pre>
     *
     * @param params      assertion parameters (set {@code isErrorFlow=true})
     * @param softly      soft-assertion collector
     */
    public void assertErrorFlow(FlowAssertionParams params, SoftAssertions softly) throws Exception {
        assertCustomFlow(params, softly);
    }

    /**
     * Fully-parameterised assertion method that covers every {@code list.json}
     * combination. All convenience methods delegate here.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>List objects in the data bucket and locate the payload / ACK keys.</li>
     *   <li>List objects in the metadata bucket and read the metadata JSON.</li>
     *   <li>Extract {@code interactionId}, {@code timestamp}, and optional
     *       {@code fileName} from the metadata JSON to build <em>expected</em>
     *       key patterns.</li>
     *   <li>Verify prefix, suffix, and full {@code s3://} URI consistency.</li>
     *   <li>If not an error flow and a queue URL is supplied, poll SQS and verify
     *       all path fields and {@code messageGroupId}.</li>
     * </ol>
     *
     * @param params assertion parameters
     * @param softly soft-assertion collector
     */
    public void assertCustomFlow(FlowAssertionParams params, SoftAssertions softly) throws Exception {

        // ── 1. Locate objects in the data bucket ─────────────────────────────
        ListObjectsV2Response dataObjects = s3Client.listObjectsV2(
                ListObjectsV2Request.builder().bucket(params.dataBucket).build());

        softly.assertThat(dataObjects.contents())
                .as("[S3] Data bucket '%s' must not be empty", params.dataBucket)
                .isNotEmpty();
        if (dataObjects.contents().isEmpty()) return;

        String payloadKey = findKey(dataObjects, k -> !k.contains("_ack") && !k.contains("_metadata"));
        String ackKey     = params.ackExpected ? findKey(dataObjects, k -> k.contains("_ack")) : null;

        softly.assertThat(payloadKey).as("[S3] Payload key must exist in '%s'", params.dataBucket).isNotNull();
        if (params.ackExpected) {
            softly.assertThat(ackKey).as("[S3] ACK key must exist in '%s'", params.dataBucket).isNotNull();
        }
        if (payloadKey == null) return;

        // ── 2. Locate metadata object ────────────────────────────────────────
        String metaBucket = params.metadataBucket != null ? params.metadataBucket : params.dataBucket;
        ListObjectsV2Response metaObjects = s3Client.listObjectsV2(
                ListObjectsV2Request.builder().bucket(metaBucket).build());

        softly.assertThat(metaObjects.contents())
                .as("[S3] Metadata bucket '%s' must not be empty", metaBucket)
                .isNotEmpty();
        if (metaObjects.contents().isEmpty()) return;

        String metadataKey = findKey(metaObjects, k -> k.contains("_metadata.json"));
        softly.assertThat(metadataKey).as("[S3] Metadata key must exist in '%s'", metaBucket).isNotNull();
        if (metadataKey == null) return;

        // ── 3. Parse metadata JSON and extract canonical IDs ─────────────────
        String metadataContent = readS3(metaBucket, metadataKey);
        JsonNode meta = MAPPER.readTree(metadataContent);

        String keyFromMeta   = meta.get("key").asText();       // the stored objectKey
        JsonNode jsonMeta    = meta.get("json_metadata");

        String interactionId = jsonMeta.has("interactionId") ? jsonMeta.get("interactionId").asText() : null;
        String timestamp     = jsonMeta.has("timestamp")     ? jsonMeta.get("timestamp").asText()     : null;
        String fileName      = jsonMeta.has("fileName")      ? jsonMeta.get("fileName").asText()      : null;
        String s3DataPath    = jsonMeta.get("s3DataObjectPath").asText();
        String s3MetaPath    = jsonMeta.get("fullS3MetaDataPath").asText();
        String s3AckPath     = params.ackExpected && jsonMeta.has("fullS3AcknowledgementPath")
                               ? jsonMeta.get("fullS3AcknowledgementPath").asText() : null;

        // ── 4. Build expected key structures ─────────────────────────────────
        String datePath = todayDatePath();

        // Build the expected file stem from metadata-derived values
        String expectedFileStem = buildExpectedFileStem(interactionId, timestamp, fileName, params);

        // Determine expected prefix for data key
        String expectedDataPrefix = buildExpectedDataPrefix(params, datePath);
        // Determine expected prefix for metadata key
        String expectedMetaPrefix = buildExpectedMetaPrefix(params, datePath);

        // ── 5. Assert key prefixes ────────────────────────────────────────────
        softly.assertThat(payloadKey)
                .as("[S3] Payload key must start with '%s'", expectedDataPrefix)
                .startsWith(expectedDataPrefix);

        softly.assertThat(metadataKey)
                .as("[S3] Metadata key must start with '%s'", expectedMetaPrefix)
                .startsWith(expectedMetaPrefix);

        // ── 6. Assert file-stem / suffix consistency ──────────────────────────
        if (expectedFileStem != null) {
            softly.assertThat(payloadKey)
                    .as("[S3] Payload key must contain file stem '%s'", expectedFileStem)
                    .contains(expectedFileStem);

            softly.assertThat(metadataKey)
                    .as("[S3] Metadata key must contain file stem '%s'", expectedFileStem)
                    .contains(expectedFileStem);

            if (params.ackExpected && ackKey != null) {
                // For hold flow the stem is just "{timestamp}_" (no fixed filename), so
                // we verify the key contains the timestamp anchor AND ends with "_ack".
                // For normal flow the stem is "{interactionId}_{timestamp}", so the full
                // suffix "{stem}_ack" is the right check.
                if (params.isHoldFlow) {
                    softly.assertThat(ackKey)
                            .as("[S3] ACK key must contain timestamp stem '%s' and end with '_ack'",
                                    expectedFileStem)
                            .contains(expectedFileStem)
                            .endsWith("_ack");
                } else {
                    softly.assertThat(ackKey)
                            .as("[S3] ACK key must contain file stem + '_ack': '%s_ack'", expectedFileStem)
                            .contains(expectedFileStem + "_ack");
                }
            }
        }

        // ── 7. Assert full s3:// URI consistency ──────────────────────────────
        softly.assertThat(s3DataPath)
                .as("[S3] s3DataObjectPath must equal s3://%s/%s", params.dataBucket, keyFromMeta)
                .isEqualTo("s3://" + params.dataBucket + "/" + keyFromMeta);

        softly.assertThat(s3MetaPath)
                .as("[S3] fullS3MetaDataPath must equal s3://%s/%s", metaBucket, metadataKey)
                .isEqualTo("s3://" + metaBucket + "/" + metadataKey);

        if (params.ackExpected && s3AckPath != null && ackKey != null) {
            softly.assertThat(s3AckPath)
                    .as("[S3] fullS3AcknowledgementPath must equal s3://%s/%s", params.dataBucket, ackKey)
                    .isEqualTo("s3://" + params.dataBucket + "/" + ackKey);
        }

        // ── 8. Optional payload content verification ──────────────────────────
        if (params.expectedPayload != null) {
            String actualPayload = readS3(params.dataBucket, payloadKey);
            softly.assertThat(params.normalizePayload(actualPayload))
                    .as("[S3] Stored payload must match sent request")
                    .isEqualTo(params.normalizePayload(params.expectedPayload));
        }

        // ── 9. Optional ACK content verification ─────────────────────────────
        if (params.ackExpected && params.ackXPathAssertions != null && ackKey != null) {
            String actualAck = readS3(params.dataBucket, ackKey);
            params.ackXPathAssertions.accept(actualAck, softly);
        }

        // ── 10. SQS consistency check (skipped for error flow) ───────────────
        if (!params.isErrorFlow && params.queueUrl != null) {
            assertSqsConsistency(
                    params.queueUrl,
                    s3DataPath,
                    s3MetaPath,
                    s3AckPath,
                    keyFromMeta,
                    params.expectedMessageGroupId,
                    softly);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SQS assertion
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Polls {@code queueUrl} and validates all SQS message fields.
     *
     * <p>Fields checked: {@code s3DataObjectPath}, {@code fullS3MetaDataPath},
     * {@code fullS3AcknowledgementPath} (if not null), {@code s3ObjectId},
     * {@code messageGroupId}.
     */
    public void assertSqsConsistency(
            String queueUrl,
            String expectedDataPath,
            String expectedMetaPath,
            String expectedAckPath,
            String expectedObjectId,
            String expectedGroupId,
            SoftAssertions softly) throws Exception {

        ReceiveMessageResponse sqsResponse = waitForSqsMessage(queueUrl);
        softly.assertThat(sqsResponse.messages())
                .as("[SQS] Queue '%s' must contain a message", queueUrl)
                .isNotEmpty();
        if (sqsResponse.messages().isEmpty()) return;

        Message msg = sqsResponse.messages().get(0);
        JsonNode sqsJson = MAPPER.readTree(msg.body());

        softly.assertThat(sqsJson.get("s3DataObjectPath").asText())
                .as("[SQS] s3DataObjectPath").isEqualTo(expectedDataPath);

        softly.assertThat(sqsJson.get("fullS3MetaDataPath").asText())
                .as("[SQS] fullS3MetaDataPath").isEqualTo(expectedMetaPath);

        if (expectedAckPath != null) {
            softly.assertThat(sqsJson.get("fullS3AcknowledgementPath").asText())
                    .as("[SQS] fullS3AcknowledgementPath").isEqualTo(expectedAckPath);
        }

        softly.assertThat(sqsJson.get("s3ObjectId").asText())
                .as("[SQS] s3ObjectId").isEqualTo(expectedObjectId);

        if (expectedGroupId != null) {
            softly.assertThat(sqsJson.get("messageGroupId").asText())
                    .as("[SQS] messageGroupId").isEqualTo(expectedGroupId);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // S3 bucket content helpers (for direct use in diagnostic tests)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Returns all keys in {@code bucket}. */
    public List<String> listKeys(String bucket) {
        return s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
                .contents().stream().map(S3Object::key).collect(Collectors.toList());
    }

    /** Reads an S3 object as a UTF-8 string. */
    public String readS3(String bucket, String key) {
        return s3Client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build()).asUtf8String();
    }

    /** Returns true if {@code bucket} has at least one object. */
    public boolean bucketHasObjects(String bucket) {
        return !s3Client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
                .contents().isEmpty();
    }

    /** Returns true if {@code bucket} is empty. */
    public boolean bucketIsEmpty(String bucket) {
        return !bucketHasObjects(bucket);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Date / prefix utilities
    // ═══════════════════════════════════════════════════════════════════════════

    /** {@code YYYY/MM/DD} — date path used inside all S3 key prefixes. */
    public static String todayDatePath() {
        LocalDate d = LocalDate.now();
        return String.format("%d/%02d/%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth());
    }

    /** {@code data/YYYY/MM/DD} — default data prefix (no dataDir). */
    public static String todayDefaultDataPrefix() {
        return PREFIX_DATA + "/" + todayDatePath();
    }

    /** {@code metadata/YYYY/MM/DD} — default metadata prefix (no metadataDir). */
    public static String todayDefaultMetadataPrefix() {
        return "metadata/" + todayDatePath();
    }

    /** {@code error/YYYY/MM/DD} — error prefix (never has dataDir applied). */
    public static String todayErrorPrefix() {
        return PREFIX_ERROR + "/" + todayDatePath();
    }

    /**
     * Builds the expected data key prefix from flow params.
     *
     * <p>Rules (mirrors {@code DataDirResolverImpl}):
     * <ul>
     *   <li>Error flow → {@code error/YYYY/MM/DD} (no dataDir)</li>
     *   <li>Hold + tenant → {@code {dataDir}/hold/{tenantId}/YYYY/MM/DD}</li>
     *   <li>Hold + port  → {@code {dataDir}/hold/{port}/YYYY/MM/DD}</li>
     *   <li>Normal + tenant → {@code {dataDir}/data/{tenantId}/YYYY/MM/DD}</li>
     *   <li>Normal + no dir → {@code data/YYYY/MM/DD}</li>
     * </ul>
     */
    public static String buildExpectedDataPrefix(FlowAssertionParams p, String datePath) {
        if (p.isErrorFlow) {
            return PREFIX_ERROR + "/" + datePath;
        }
        if (p.isHoldFlow) {
            String base = stripSlashes(p.dataDir);
            String tenantOrPort = p.tenantId != null ? p.tenantId
                    : (p.port > 0 ? String.valueOf(p.port) : null);
            if (tenantOrPort != null) {
                return base + "/" + HOLD_SEGMENT + "/" + tenantOrPort + "/" + datePath;
            }
            return base + "/" + HOLD_SEGMENT + "/" + datePath;
        }
        // Normal flow
        String base = (p.dataDir != null && !p.dataDir.isBlank())
                ? stripSlashes(p.dataDir) + "/" + PREFIX_DATA
                : PREFIX_DATA;
        if (p.tenantId != null) {
            return base + "/" + p.tenantId + "/" + datePath;
        }
        return base + "/" + datePath;
    }

    /**
     * Builds the expected metadata key prefix from flow params.
     *
     * <p>Mirrors {@code DataDirResolverImpl#buildHoldMetadataKey} /
     * {@code buildNormalMetadataKey}.
     */
    public static String buildExpectedMetaPrefix(FlowAssertionParams p, String datePath) {
        if (p.isErrorFlow) {
            return PREFIX_ERROR + "/" + datePath;
        }
        if (p.isHoldFlow) {
            String base = stripSlashes(p.metadataDir != null ? p.metadataDir : p.dataDir);
            String tenantOrPort = p.tenantId != null ? p.tenantId
                    : (p.port > 0 ? String.valueOf(p.port) : null);
            if (tenantOrPort != null) {
                return base + "/" + HOLD_META_SEGMENT + "/" + tenantOrPort + "/" + datePath;
            }
            return base + "/" + HOLD_META_SEGMENT + "/" + datePath;
        }
        // Normal flow
        String metaBase = (p.metadataDir != null && !p.metadataDir.isBlank())
                ? stripSlashes(p.metadataDir) + "/metadata"
                : "metadata";
        if (p.tenantId != null) {
            return metaBase + "/" + p.tenantId + "/" + datePath;
        }
        return metaBase + "/" + datePath;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Builds the expected file stem used to verify key names.
     *
     * <p>For hold flow: returns just {@code "{timestamp}_"} as a prefix-anchor.
     * The full file name (including extension) is preserved by the application
     * ({@code DataDirResolverImpl#buildTimestampedName}), so we only assert that
     * the key contains the timestamp prefix — avoiding false failures caused by
     * extension stripping (e.g. {@code soap-message.xml} must not become
     * {@code soap-message}).
     *
     * <p>For normal flow: {@code {interactionId}_{timestamp}}
     */
    private static String buildExpectedFileStem(
            String interactionId, String timestamp, String fileName, FlowAssertionParams p) {

        if (timestamp == null) return null; // can't derive without timestamp

        if (p.isHoldFlow) {
            // Only anchor on the timestamp prefix; let the full filename+extension be
            // whatever the application wrote — verified indirectly via the s3:// URI check.
            return timestamp + "_";
        }
        if (interactionId != null) {
            return interactionId + "_" + timestamp;
        }
        return timestamp;
    }

    private static String stripSlashes(String path) {
        if (path == null) return "";
        return path.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private static String findKey(ListObjectsV2Response response,
                                   java.util.function.Predicate<String> predicate) {
        return response.contents().stream()
                .map(S3Object::key)
                .filter(predicate)
                .findFirst()
                .orElse(null);
    }

    /**
     * Polls {@code queueUrl} (up to 10 × 2 s) until a message arrives.
     */
    private ReceiveMessageResponse waitForSqsMessage(String queueUrl) throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            ReceiveMessageResponse resp = sqsClient.receiveMessage(
                    ReceiveMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .waitTimeSeconds(2)
                            .build());
            if (!resp.messages().isEmpty()) return resp;
            Thread.sleep(1_000);
        }
        throw new AssertionError("No SQS message received after retries for queue: " + queueUrl);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FlowAssertionParams – builder-style configuration object
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Encapsulates all parameters needed to assert a single ingestion flow.
     *
     * <p>Use the fluent {@link Builder} to construct instances:
     * <pre>{@code
     * FlowAssertionParams params = FlowAssertionParams.builder()
     *     .dataBucket(DEFAULT_DATA_BUCKET)
     *     .metadataBucket(DEFAULT_METADATA_BUCKET)
     *     .queueUrl(mainQueueUrl)
     *     .expectedMessageGroupId("ORU_PUSH")
     *     .expectedPayload(hl7)
     *     .payloadNormalizer(IngestionAssertionHelper::normalizeHl7)
     *     .build();
     * }</pre>
     */
    public static final class FlowAssertionParams {

        // ── Bucket config ──────────────────────────────────────────────────────
        /** S3 bucket that holds the raw payload (and ACK). Required. */
        public final String dataBucket;
        /**
         * S3 bucket that holds the metadata JSON. If null, defaults to
         * {@code dataBucket} (hold flow has both in one bucket).
         */
        public final String metadataBucket;

        // ── Flow type flags ────────────────────────────────────────────────────
        /** True when the port-config has {@code route=/hold}. */
        public final boolean isHoldFlow;
        /**
         * True when the ingestion failed and data lands in the {@code error/}
         * prefix. SQS assertion is skipped for error flows.
         */
        public final boolean isErrorFlow;

        // ── Port-config attributes (from list.json) ────────────────────────────
        /** The listening port (used in hold-key path when no tenant). */
        public final int port;
        /** {@code dataDir} from port-config (e.g. {@code "/outbound"}). */
        public final String dataDir;
        /** {@code metadataDir} from port-config; falls back to dataDir if null. */
        public final String metadataDir;
        /**
         * Tenant ID ({@code sourceId_msgType}). Null if neither field is set in
         * the port-config entry.
         */
        public final String tenantId;

        // ── SQS config ─────────────────────────────────────────────────────────
        /** Queue URL to poll. Null skips SQS check. */
        public final String queueUrl;
        /**
         * Expected {@code messageGroupId} in the SQS message body. Null skips
         * that specific field assertion.
         */
        public final String expectedMessageGroupId;

        // ── Payload / ACK ──────────────────────────────────────────────────────
        /**
         * Raw payload that was sent. When non-null the stored S3 object is
         * compared after normalisation.
         */
        public final String expectedPayload;
        /** Whether to look for an ACK object ({@code _ack} suffix). */
        public final boolean ackExpected;
        /**
         * Optional lambda to assert fields inside the stored ACK. Signature:
         * {@code (ackXml, softly) -> ...}
         */
        public final AckAssertion ackXPathAssertions;
        /**
         * Normalizer applied to both the sent and stored payload before comparison.
         * Defaults to {@link IngestionAssertionHelper#normalizeGeneric}.
         */
        final PayloadNormalizer payloadNormalizer;

        private FlowAssertionParams(Builder b) {
            this.dataBucket              = b.dataBucket;
            this.metadataBucket          = b.metadataBucket;
            this.isHoldFlow              = b.isHoldFlow;
            this.isErrorFlow             = b.isErrorFlow;
            this.port                    = b.port;
            this.dataDir                 = b.dataDir;
            this.metadataDir             = b.metadataDir;
            this.tenantId                = b.tenantId;
            this.queueUrl                = b.queueUrl;
            this.expectedMessageGroupId  = b.expectedMessageGroupId;
            this.expectedPayload         = b.expectedPayload;
            this.ackExpected             = b.ackExpected;
            this.ackXPathAssertions      = b.ackXPathAssertions;
            this.payloadNormalizer        = b.payloadNormalizer != null
                                            ? b.payloadNormalizer
                                            : IngestionAssertionHelper::normalizeGeneric;
        }

        public String normalizePayload(String raw) {
            return payloadNormalizer.normalize(raw);
        }

        public static Builder builder() { return new Builder(); }

        /**
         * Returns a new {@link Builder} pre-populated with all values from this
         * instance, allowing callers to clone-and-override specific fields.
         *
         * <pre>{@code
         * FlowAssertionParams noAck = defaultFlowParams(request, null, groupId)
         *         .toBuilder()
         *         .ackExpected(false)
         *         .build();
         * }</pre>
         */
        public Builder toBuilder() {
            return new Builder()
                    .dataBucket(this.dataBucket)
                    .metadataBucket(this.metadataBucket)
                    .holdFlow(this.isHoldFlow)
                    .errorFlow(this.isErrorFlow)
                    .port(this.port)
                    .dataDir(this.dataDir)
                    .metadataDir(this.metadataDir)
                    .tenantId(this.tenantId)
                    .queueUrl(this.queueUrl)
                    .expectedMessageGroupId(this.expectedMessageGroupId)
                    .expectedPayload(this.expectedPayload)
                    .ackExpected(this.ackExpected)
                    .ackXPathAssertions(this.ackXPathAssertions)
                    .payloadNormalizer(this.payloadNormalizer);
        }

        public static final class Builder {
            private String dataBucket;
            private String metadataBucket;
            private boolean isHoldFlow;
            private boolean isErrorFlow;
            private int port;
            private String dataDir;
            private String metadataDir;
            private String tenantId;
            private String queueUrl;
            private String expectedMessageGroupId;
            private String expectedPayload;
            private boolean ackExpected = true;
            private AckAssertion ackXPathAssertions;
            private PayloadNormalizer payloadNormalizer;

            /** S3 bucket for data payload + ACK. */
            public Builder dataBucket(String v)             { dataBucket = v;             return this; }
            /** S3 bucket for metadata JSON (null → same as dataBucket). */
            public Builder metadataBucket(String v)         { metadataBucket = v;         return this; }
            /** Mark as hold flow ({@code route=/hold}). */
            public Builder holdFlow(boolean v)              { isHoldFlow = v;             return this; }
            /** Mark as error flow (payload lands in {@code error/…}). */
            public Builder errorFlow(boolean v)             { isErrorFlow = v;            return this; }
            /** Listening port number (used in hold-key when no tenant). */
            public Builder port(int v)                      { port = v;                   return this; }
            /** {@code dataDir} from port-config entry. */
            public Builder dataDir(String v)                { dataDir = v;                return this; }
            /** {@code metadataDir} from port-config entry. */
            public Builder metadataDir(String v)            { metadataDir = v;            return this; }
            /** Tenant ID ({@code sourceId_msgType}); null if absent. */
            public Builder tenantId(String v)               { tenantId = v;               return this; }
            /** SQS queue URL to poll; null skips SQS assertion. */
            public Builder queueUrl(String v)               { queueUrl = v;               return this; }
            /** Expected {@code messageGroupId}; null skips that check. */
            public Builder expectedMessageGroupId(String v) { expectedMessageGroupId = v; return this; }
            /** Raw payload that was sent (for stored-payload comparison). */
            public Builder expectedPayload(String v)        { expectedPayload = v;        return this; }
            /** Whether to assert an ACK object exists (default true). */
            public Builder ackExpected(boolean v)           { ackExpected = v;            return this; }
            /** Optional lambda to assert content inside the stored ACK. */
            public Builder ackXPathAssertions(AckAssertion v) { ackXPathAssertions = v;  return this; }
            /** Custom payload normalizer (default: {@link IngestionAssertionHelper#normalizeGeneric}). */
            public Builder payloadNormalizer(PayloadNormalizer v) { payloadNormalizer = v; return this; }

            public FlowAssertionParams build() {
                if (dataBucket == null) throw new IllegalStateException("dataBucket is required");
                return new FlowAssertionParams(this);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Functional interfaces
    // ═══════════════════════════════════════════════════════════════════════════

    @FunctionalInterface
    public interface PayloadNormalizer {
        String normalize(String raw);
    }

    @FunctionalInterface
    public interface AckAssertion {
        void accept(String ackContent, SoftAssertions softly);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Built-in normalizers (static, for convenience)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Collapses all whitespace – works for both XML and HL7. */
    public static String normalizeGeneric(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** XML-aware: also collapses inter-element whitespace. */
    public static String normalizeXml(String xml) {
        return xml == null ? "" : xml.replaceAll(">\\s+<", "><").replaceAll("\\s+", " ").trim();
    }

    /** HL7 normalizer (alias of {@link #normalizeGeneric}). */
    public static String normalizeHl7(String hl7) {
        return normalizeGeneric(hl7);
    }
}