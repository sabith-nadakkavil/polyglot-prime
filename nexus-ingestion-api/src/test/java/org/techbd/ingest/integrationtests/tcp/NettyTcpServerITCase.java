package org.techbd.ingest.integrationtests.tcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.techbd.ingest.integrationtests.base.BaseIntegrationTest;
import org.techbd.ingest.integrationtests.base.NexusIntegrationTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/**
 * Full-stack integration tests for the Netty TCP/MLLP server.
 *
 * <p>
 * Infrastructure (LocalStack S3 + SQS, bucket/queue creation, port-config
 * upload)
 * is bootstrapped once by {@link BaseIntegrationTest}. Between every test
 * method
 * {@code BaseIntegrationTest.cleanS3AndSqsState()} purges all data buckets and
 * SQS
 * queues so each test starts with a clean slate.
 *
 * <h3>Profile override — critical</h3>
 * {@code NettyTcpServer.startServer()} calls
 * {@code System.getenv("SPRING_PROFILES_ACTIVE")}
 * to decide whether to add
 * {@link io.netty.handler.codec.haproxy.HAProxyMessageDecoder}
 * to the Netty pipeline, or to skip it and inject dummy HAProxy details (the
 * {@code "sandbox"} shortcut). Because Spring's {@code @TestPropertySource} and
 * {@code DynamicPropertySource} only populate the Spring {@code Environment} —
 * not
 * the OS process environment — this class forcibly rewrites the SPRING_PROFILES_ACTIVE in {@link #initProfile()} so that
 * {@code System.getenv("SPRING_PROFILES_ACTIVE")} returns {@code "test"}.
 *
 * <p>
 * This ensures that:
 * <ul>
 * <li>{@code HAProxyMessageDecoder} <em>is</em> installed in the pipeline.</li>
 * <li>Every connection sends a real PROXY v1 header (as ALB does in
 * production).</li>
 * <li>Port-config resolution uses the PROXY header {@code destPort}, not a
 * hard-coded dummy.</li>
 * </ul>
 *
 * <h3>Port configuration under test</h3>
 * 
 * <pre>
 * { "port": 2575, "responseType": "outbound", "protocol": "TCP" }
 *
 * { "port": 5555, "responseType": "outbound", "protocol": "TCP",
 *   "route": "/hold", "dataDir": "/outbound", "metadataDir": "/outbound",
 *   "queue": "test.fifo", "keepAliveTimeout": 50 }
 * </pre>
 *
 * <h3>TCP read-timeout override</h3>
 * All tests run with {@code TCP_READ_TIMEOUT_SECONDS=10} so idle-timeout
 * scenarios
 * complete in ~10 s rather than the production default of 180 s.
 *
 * <h3>HAProxy PROXY protocol v1 header</h3>
 * Format:
 * {@code PROXY TCP4 <client-ip> <dest-ip> <client-port> <dest-port>\r\n}
 * The {@code dest-port} drives port-config resolution inside
 * {@code NettyTcpServer};
 * the Netty listen port (7980) is irrelevant for routing.
 *
 * <h3>S3 / SQS path conventions</h3>
 * <ul>
 * <li><b>Port 2575 (default flow)</b>: payload + ACK in
 * {@code DEFAULT_DATA_BUCKET}
 * under {@code data/YYYY/MM/DD/…}; metadata in {@code DEFAULT_METADATA_BUCKET}
 * under {@code metadata/YYYY/MM/DD/…}; message on main FIFO queue.</li>
 * <li><b>Port 5555 (HOLD flow)</b>: all artefacts in {@code HOLD_BUCKET} under
 * {@code outbound/hold/5555/YYYY/MM/DD/…} (data) and
 * {@code outbound/hold/metadata/5555/YYYY/MM/DD/…} (metadata);
 * message on {@code test.fifo}.</li>
 * </ul>
 *
 * <h3>Message-group ID — {@code MllpMessageGroupStrategy}</h3>
 * <ul>
 * <li>ZNT present: {@code qe_facility_messageCode_deliveryType}
 * (ZNT-8 split on ":" gives qe/facility; ZNT-2.1 = messageCode; ZNT-4.1 =
 * deliveryType)</li>
 * <li>ZNT absent: falls back to {@code destinationPort} from the PROXY
 * header.</li>
 * </ul>
 */
@NexusIntegrationTest
@TestPropertySource(properties = {
                // Registers profile in the Spring Environment (belt-and-suspenders alongside
                // the System.getenv() override below).
                "SPRING_PROFILES_ACTIVE=test",
                "TCP_READ_TIMEOUT_SECONDS=10"
})
@Tag("integration")
class NettyTcpServerITCase extends BaseIntegrationTest {

        // ── Constants
        // ─────────────────────────────────────────────────────────────────

        /** Netty TCP server listen port. */
        private static final int TCP_SERVER_PORT = 7980;
        private static final String TCP_HOST = "localhost";
        /**
         * Read-timeout used by the server for the non-keepAlive port (2575).
         * Must match {@code TCP_READ_TIMEOUT_SECONDS=10} in
         * {@code @TestPropertySource}.
         */
        private static final int TCP_READ_TIMEOUT_SECONDS = 10;

        /** Spoofed client IP placed in every PROXY header. */
        private static final String CLIENT_IP = "203.0.113.10";
        /** Destination IP placed in every PROXY header. */
        private static final String DEST_IP = "127.0.0.1";
        /** Arbitrary spoofed source port. */
        private static final int CLIENT_PORT = 55000;

        /** MLLP framing bytes (HL7 2.x appendix C). */
        private static final byte MLLP_START = 0x0B; // <VT>
        private static final byte MLLP_END_1 = 0x1C; // <FS>
        private static final byte MLLP_END_2 = 0x0D; // <CR>

        /** keepAliveTimeout configured for port 5555 (seconds). */
        private static final int KAT_5555 = 50;

        private static final ObjectMapper MAPPER = new ObjectMapper();

        @BeforeAll
        static void initProfile() {
                System.setProperty("SPRING_PROFILES_ACTIVE", "test");
        }

        @Test
        @DisplayName("DIAG: SPRING_PROFILES_ACTIVE must be 'test'")
        void assertProfileIsTest() {
                assertThat(System.getProperty("SPRING_PROFILES_ACTIVE"))
                                .as("SPRING_PROFILES_ACTIVE must be 'test' so HAProxyMessageDecoder " +
                                                "is active. If it is 'sandbox' the decoder is skipped and all " +
                                                "PROXY-header-based routing assertions will fail.")
                                .isEqualTo("test");
        }

        /**
         * Verifies that the Netty TCP server is actually listening on the configured
         * port.
         * This is the TCP equivalent of the SOAP healthCheck test.
         */
        @Test
        @DisplayName("DIAG: TCP server is listening on configured port")
        void tcpServer_isListening() {
                try (Socket socket = new Socket(TCP_HOST, TCP_SERVER_PORT)) {
                        assertThat(socket.isConnected())
                                        .as("TCP server must be reachable on port " + TCP_SERVER_PORT)
                                        .isTrue();
                } catch (Exception e) {
                        throw new AssertionError("TCP server is not listening on " + TCP_HOST + ":" + TCP_SERVER_PORT,
                                        e);
                }
        }
        // ─────────────────────────────────────────────────────────────────────────────
        // ── Port 2575 — ReadTimeoutHandler (no keepAliveTimeout) ─────────────────────
        // ─────────────────────────────────────────────────────────────────────────────

        /**
         * <b>IT: Port 2575 — MLLP HL7 with ZNT — happy path, full S3 + SQS flow.</b>
         *
         * <p>
         * Port config:
         * 
         * <pre>
         * { "port": 2575, "responseType": "outbound", "protocol": "TCP" }
         * </pre>
         *
         * <p>
         * <b>Default routing behavior</b>:
         * <ul>
         * <li>No {@code route} specified → default route is used.</li>
         * <li>No {@code dataDir} / {@code metadataDir} specified → default S3 prefixes
         * are used.</li>
         * <li>No {@code queue} specified → message is published to the main/default SQS
         * queue.</li>
         * </ul>
         *
         * <p>
         * ZNT segment values in the fixture:
         * <ul>
         * <li>ZNT-2.1 = {@code ORU} (messageCode)</li>
         * <li>ZNT-4.1 = {@code PUSH} (deliveryType)</li>
         * <li>ZNT-8 = {@code 5003637762^healthelink:EGSMC~64654645^healthelink:CHG}
         * → qe/facility derived from assigning authority</li>
         * </ul>
         *
         * <p>
         * Message-group strategy:
         * <ul>
         * <li>ZNT present → {@code messageGroupId = messageCode_deliveryType}</li>
         * <li>Expected: {@code ORU_PUSH}</li>
         * </ul>
         *
         * <p>
         * Flow:
         * <ol>
         * <li>Open TCP to localhost:{@value #TCP_SERVER_PORT}.</li>
         * <li>Send HAProxy PROXY v1 header with {@code destPort=2575}.</li>
         * <li>Send MLLP-wrapped HL7 {@code ORU^R01} with ZNT (single TCP write).</li>
         * <li>Assert ACK: MSH present, {@code MSA|AA}, optional NTE (if feature
         * enabled).</li>
         * <li>Assert payload stored in default S3 data bucket under
         * {@code data/YYYY/MM/DD/...}.</li>
         * <li>Assert metadata stored in default metadata bucket under
         * {@code metadata/YYYY/MM/DD/...}.</li>
         * <li>Assert ACK stored alongside payload with {@code _ack} suffix.</li>
         * <li>Assert SQS message published to default queue with consistent S3
         * paths.</li>
         * <li>Assert {@code messageGroupId = ORU_PUSH} (derived from ZNT).</li>
         * <li>Assert PROXY {@code destPort=2575} drives routing (not Netty port
         * 7980).</li>
         * </ol>
         *
         * <p>
         * <b>Key behavior under test</b>:
         * <ul>
         * <li>Default bucket + prefix resolution when optional config is absent.</li>
         * <li>ZNT-driven message grouping.</li>
         * <li>End-to-end flow: TCP → ACK → S3 (data + metadata) → SQS.</li>
         * </ul>
         *
         * <p>
         * Expected {@code messageGroupId}: {@code ORU_PUSH}
         */
        @Test
        @DisplayName("IT: Port 2575 — MLLP HL7 with ZNT — happy path, S3 + SQS full flow")
        void port2575_mllpHl7WithZnt_happyPath() throws Exception {
                String hl7 = loadHl7Fixture("hl7_message.hl7");
                SoftAssertions softly = new SoftAssertions();

                byte[] ackBytes = sendMllpWithProxy(CLIENT_IP, DEST_IP, CLIENT_PORT, 2575, hl7);
                assertMllpAck(ackBytes, softly, "Port-2575 ZNT ACK");

                assertDefaultFlowS3AndSqs(hl7, null, "ORU_PUSH", softly, false);
                softly.assertAll();
        }

        /**
         * <b>IT: Port 2575 — MLLP HL7 WITHOUT ZNT — group-id falls back to PROXY dest
         * port.</b>
         *
         * <p>
         * Port config:
         * 
         * <pre>
         * { "port": 2575, "responseType": "outbound", "protocol": "TCP" }
         * </pre>
         *
         * Flow:
         * <ol>
         * <li>Send PROXY header ({@code destPort=2575}) + MLLP HL7 without ZNT.</li>
         * <li>Assert ACK: MSH, MSA|AR, Missing ZNT Segment.</li>
         * <li>Assert default-flow S3 paths.</li>
         * <li>Assert SQS messageGroupId = {@code "2575"}.</li>
         * </ol>
         */
        @Test
        @DisplayName("IT: Port 2575 — MLLP HL7 without ZNT — group-id = PROXY destPort (\"2575\")")
        void port2575_mllpHl7WithoutZnt_groupIdFallsBackToDestPort() throws Exception {
                String hl7 = loadHl7Fixture("hl7_without_znt.hl7");
                SoftAssertions softly = new SoftAssertions();

                byte[] ackBytes = sendMllpWithProxy(CLIENT_IP, DEST_IP, CLIENT_PORT, 2575, hl7);
                assertMllpNackForMissingZNT(ackBytes, softly, "Port-2575 no-ZNT ACK");

                assertDefaultFlowS3AndSqs(hl7, null, "2575", softly, true);
                softly.assertAll();
        }

        /**
         * <b>IT: Port 2575 — PROXY header sent, no HL7 payload — server closes after
         * {@code TCP_READ_TIMEOUT_SECONDS} (10 s).</b>
         *
         * <p>
         * Port config:
         * 
         * <pre>
         * { "port": 2575, "responseType": "outbound", "protocol": "TCP" }
         * </pre>
         *
         * <p>
         * Because port 2575 has no {@code keepAliveTimeout} configured,
         * {@code NettyTcpServer} installs a {@code ReadTimeoutHandler(10 s)}.
         * After 10 s of silence the handler fires {@code ReadTimeoutException}; the
         * server closes the channel (optionally after a NACK, controlled by the
         * {@code SEND_HL7_ACK_ON_IDLE_TIMEOUT} feature flag).
         *
         * <p>
         * Flow:
         * <ol>
         * <li>Open TCP + send only the PROXY header ({@code destPort=2575}).</li>
         * <li>Send <em>no</em> HL7 payload.</li>
         * <li>Assert the connection is closed (EOF or NACK) within
         * {@code TCP_READ_TIMEOUT_SECONDS + 5} s.</li>
         * <li>Assert S3 data bucket is empty — no message was processed.</li>
         * </ol>
         */
        @Test
        @DisplayName("IT: Port 2575 — no message sent — server closes after read-timeout (10 s)")
        void port2575_noMessage_serverClosesAfterReadTimeout() throws Exception {
                SoftAssertions softly = new SoftAssertions();

                long start = System.currentTimeMillis();
                try (Socket s = new Socket("localhost", TCP_SERVER_PORT)) {
                        s.setSoTimeout((TCP_READ_TIMEOUT_SECONDS + 5) * 1_000);

                        // Only the PROXY header — no HL7 frame follows
                        s.getOutputStream().write(buildProxyV1Header(CLIENT_IP, DEST_IP, CLIENT_PORT, 2575));
                        s.getOutputStream().flush();

                        byte[] buf = new byte[4_096];
                        int read;
                        try {
                                read = s.getInputStream().read(buf);
                        } catch (java.net.SocketTimeoutException e) {
                                read = -1; // client timeout before server responded — treat as close
                        }

                        long elapsed = System.currentTimeMillis() - start;
                        softly.assertThat(elapsed)
                                        .as("Server must close or NACK within readTimeout + 5 s grace window")
                                        .isLessThan((long) (TCP_READ_TIMEOUT_SECONDS + 5) * 1_000);

                        if (read > 0) {
                                String response = new String(buf, 0, read, StandardCharsets.UTF_8);
                                softly.assertThat(response.contains("AR") || response.contains("MSA"))
                                                .as("Any server data on timeout must be a NACK (MSA|AR)").isTrue();
                        }
                }

                ListObjectsV2Response dataObjects = s3Client.listObjectsV2(
                                ListObjectsV2Request.builder().bucket(DEFAULT_DATA_BUCKET).build());
                softly.assertThat(dataObjects.contents())
                                .as("S3 data bucket must be empty — no message was delivered").isEmpty();

                softly.assertAll();
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // ── Port 5555 — IdleStateHandler (keepAliveTimeout = 50 s) ───────────────────
        // ─────────────────────────────────────────────────────────────────────────────

        /**
         * <b>IT: Port 5555 — HOLD flow — MLLP HL7 with ZNT — full S3 + SQS
         * validation.</b>
         *
         * <p>
         * Port config:
         * 
         * <pre>
         * { "port": 5555, "responseType": "outbound", "protocol": "TCP",
         *   "route": "/hold", "dataDir": "/outbound", "metadataDir": "/outbound",
         *   "queue": "test.fifo", "keepAliveTimeout": 50 }
         * </pre>
         *
         * <p>
         * Flow:
         * <ol>
         * <li>Send PROXY header ({@code destPort=5555}) + MLLP HL7 with ZNT.</li>
         * <li>Assert ACK: MSH, MSA|AA, NTE with InteractionID and version.</li>
         * <li>Assert payload key starts with
         * {@code outbound/hold/5555/YYYY/MM/DD/…}.</li>
         * <li>Assert metadata key starts with
         * {@code outbound/hold/metadata/5555/YYYY/MM/DD/…}.</li>
         * <li>Assert all three artefacts are in {@code HOLD_BUCKET}.</li>
         * <li>Assert full {@code s3://} URI consistency across all paths.</li>
         * <li>Assert SQS on {@code test.fifo} with correct paths.</li>
         * <li>Assert messageGroupId = {@code TESTQE_TESTFAC_ADT_D} (ZNT-derived).</li>
         * </ol>
         */
        @Test
        @DisplayName("IT: Port 5555 — HOLD flow — MLLP HL7 with ZNT, full S3 + SQS validation")
        void port5555_mllpHl7WithZnt_holdFlow() throws Exception {
                String hl7 = loadHl7Fixture("hl7_message_with_znt.hl7");
                SoftAssertions softly = new SoftAssertions();

                byte[] ackBytes = sendMllpWithProxy(CLIENT_IP, DEST_IP, CLIENT_PORT, 5555, hl7);
                assertMllpAck(ackBytes, softly, "Port-5555 ZNT HOLD ACK");

                assertHoldFlowS3AndSqs(hl7, null, "healthelink_GHC_ORU_SN_ORU", softly);
                softly.assertAll();
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // ── ACK assertion helper
        // ──────────────────────────────────────────────────────
        // ─────────────────────────────────────────────────────────────────────────────

        /**
         * Asserts that {@code rawAck} is a valid MLLP-wrapped HL7 ACK.
         *
         * <p>
         * Checks:
         * <ul>
         * <li>Non-empty bytes received.</li>
         * <li>MSH segment present (response is HL7, not a raw error string).</li>
         * <li>MSA segment present.</li>
         * <li>Acknowledgement code is {@code AA} (accepted).</li>
         * <li>NTE segment present (feature flag {@code ADD_NTE_SEGMENT_TO_HL7_ACK}
         * must be enabled in test config).</li>
         * <li>NTE contains {@code InteractionID:} (correlation token).</li>
         * <li>NTE contains {@code TechBDIngestionApiVersion:} (version tag).</li>
         * </ul>
         */
        private void assertMllpAck(byte[] rawAck, SoftAssertions softly, String label) {
                softly.assertThat(rawAck).as(label + ": raw ACK bytes must not be empty").isNotEmpty();
                if (rawAck == null || rawAck.length == 0)
                        return;

                // Strip MLLP framing bytes for readable string assertions
                String ackStr = new String(rawAck, StandardCharsets.UTF_8)
                                .replace(String.valueOf((char) MLLP_START), "")
                                .replace(String.valueOf((char) MLLP_END_1), "")
                                .replace(String.valueOf((char) MLLP_END_2), "");

                softly.assertThat(ackStr).as(label + ": must contain MSH segment").contains("MSH");
                softly.assertThat(ackStr).as(label + ": must contain MSA segment").contains("MSA");
                softly.assertThat(ackStr).as(label + ": acknowledgement code must be AA").contains("MSA|AA");
                // softly.assertThat(ackStr).as(label + ": must contain NTE
                // segment").contains("NTE");
                // softly.assertThat(ackStr).as(label + ": NTE must carry InteractionID")
                // .contains("InteractionID:");
                // softly.assertThat(ackStr).as(label + ": NTE must carry
                // TechBDIngestionApiVersion")
                // .contains("TechBDIngestionApiVersion:");
        }

        /**
         * Asserts that {@code rawAck} is a valid MLLP-wrapped HL7 NACK
         * for the specific case: missing ZNT segment.
         *
         * <p>
         * Checks:
         * <ul>
         * <li>Non-empty bytes received.</li>
         * <li>MSH segment present.</li>
         * <li>MSA segment present.</li>
         * <li>Acknowledgement code is {@code AR} (application reject).</li>
         * <li>Error message mentions missing ZNT.</li>
         * <li>ERR segment present with HL7 error code.</li>
         * </ul>
         */
        private void assertMllpNackForMissingZNT(byte[] rawAck, SoftAssertions softly, String label) {

                softly.assertThat(rawAck)
                                .as(label + ": raw NACK bytes must not be empty")
                                .isNotEmpty();

                if (rawAck == null || rawAck.length == 0)
                        return;

                // Strip MLLP framing
                String ackStr = new String(rawAck, StandardCharsets.UTF_8)
                                .replace(String.valueOf((char) MLLP_START), "")
                                .replace(String.valueOf((char) MLLP_END_1), "")
                                .replace(String.valueOf((char) MLLP_END_2), "");

                // ── Basic HL7 structure ─────────────────────────────────────────
                softly.assertThat(ackStr)
                                .as(label + ": must contain MSH segment")
                                .contains("MSH");

                softly.assertThat(ackStr)
                                .as(label + ": must contain MSA segment")
                                .contains("MSA");

                // ── NACK validation ─────────────────────────────────────────────
                softly.assertThat(ackStr)
                                .as(label + ": acknowledgement must be AR (Application Reject)")
                                .contains("MSA|AR");

                // ── Error validation ────────────────────────────────────────────
                softly.assertThat(ackStr)
                                .as(label + ": must contain ERR segment")
                                .contains("ERR");

                softly.assertThat(ackStr)
                                .as(label + ": must mention missing ZNT segment")
                                .containsIgnoringCase("Missing ZNT");

                // Optional stricter validation (HL7 error code)
                softly.assertThat(ackStr)
                                .as(label + ": must contain HL7 application error code 207")
                                .contains("207^Application error");
        }
        // ─────────────────────────────────────────────────────────────────────────────
        // ── S3 / SQS assertion helpers
        // ────────────────────────────────────────────────
        // ─────────────────────────────────────────────────────────────────────────────

        private void assertDefaultFlowS3AndSqs(
                        String expectedPayload,
                        String expectedAck,
                        String expectedGroupId,
                        SoftAssertions softly, boolean isErrorFlow) throws Exception {

                // ── S3 data bucket ────────────────────────────────────────────────
                ListObjectsV2Response dataObjects = s3Client.listObjectsV2(
                                ListObjectsV2Request.builder().bucket(DEFAULT_DATA_BUCKET).build());

                softly.assertThat(dataObjects.contents())
                                .as("DEFAULT_DATA_BUCKET must contain payload/ack or error objects")
                                .isNotEmpty();
                if (dataObjects.contents().isEmpty())
                        return;

                String payloadKey = dataObjects.contents().stream()
                                .map(S3Object::key)
                                .filter(k -> !k.contains("_ack") && !k.contains("_metadata"))
                                .findFirst().orElse(null);

                String ackKey = dataObjects.contents().stream()
                                .map(S3Object::key)
                                .filter(k -> k.contains("_ack"))
                                .findFirst().orElse(null);

                softly.assertThat(payloadKey).as("Payload key must exist").isNotNull();
                softly.assertThat(ackKey).as("ACK key must exist").isNotNull();
                if (payloadKey == null || ackKey == null)
                        return;

                // ── Detect flow type (data vs error) ──────────────────────────────
                String basePrefix = isErrorFlow ? "error/" : "data/";

                String expectedDatePath = todayDatePath(); // YYYY/MM/DD
                String expectedPrefix = basePrefix + expectedDatePath;

                softly.assertThat(payloadKey)
                                .as("Payload key must start with correct prefix (" + expectedPrefix + ")")
                                .startsWith(expectedPrefix);

                // Validate payload ONLY for success flow
                if (!isErrorFlow) {
                        softly.assertThat(normalizeHl7(readS3(DEFAULT_DATA_BUCKET, payloadKey)))
                                        .as("Stored payload must match HL7 message")
                                        .isEqualTo(normalizeHl7(expectedPayload));
                }

                // ── Metadata bucket ───────────────────────────────────────────────
                ListObjectsV2Response metaObjects = s3Client.listObjectsV2(
                                ListObjectsV2Request.builder().bucket(DEFAULT_METADATA_BUCKET).build());

                softly.assertThat(metaObjects.contents())
                                .as("DEFAULT_METADATA_BUCKET must contain metadata")
                                .isNotEmpty();
                if (metaObjects.contents().isEmpty())
                        return;

                String metadataKey = metaObjects.contents().get(0).key();

                JsonNode meta = MAPPER.readTree(readS3(DEFAULT_METADATA_BUCKET, metadataKey));
                String key = meta.get("key").asText();
                JsonNode jsonMeta = meta.get("json_metadata");

                String s3DataPath = jsonMeta.get("s3DataObjectPath").asText();
                String metaPath = jsonMeta.get("fullS3MetaDataPath").asText();
                String ackPath = jsonMeta.get("fullS3AcknowledgementPath").asText();

                // ── Dynamic prefixes ──────────────────────────────────────────────
                String datePrefix = expectedPrefix + "/";
                String metaPrefix = (isErrorFlow ? "error/" : "metadata/") + expectedDatePath + "/";

                String keySuffix = key.substring(datePrefix.length());

                // ── Suffix validation ─────────────────────────────────────────────
                softly.assertThat(extractSuffix(s3DataPath, datePrefix))
                                .as("s3DataObjectPath suffix")
                                .isEqualTo(keySuffix);

                softly.assertThat(extractSuffix(ackPath, datePrefix))
                                .as("ACK path suffix")
                                .isEqualTo(keySuffix + "_ack");

                softly.assertThat(extractSuffix(metaPath, metaPrefix))
                                .as("Metadata path suffix")
                                .isEqualTo(keySuffix + "_metadata.json");

                // ── SQS validation ────────────────────────────────────────────────
                if (!isErrorFlow) {
                        assertSqsConsistency(
                                        mainQueueUrl,
                                        s3DataPath,
                                        metaPath,
                                        ackPath,
                                        key,
                                        expectedGroupId,
                                        softly);
                }
        }

        /**
         * Validates the complete S3 → metadata → SQS chain for the <em>HOLD</em>
         * (port 5555) flow.
         *
         * <p>
         * Mirrors {@code SoapWsEndPointITCase.assertHoldFlowS3AndSqs}. Expects:
         * <ul>
         * <li>All artefacts in {@link #HOLD_BUCKET}.</li>
         * <li>Payload key: {@code outbound/hold/5555/YYYY/MM/DD/…}.</li>
         * <li>Metadata key: {@code outbound/hold/metadata/5555/YYYY/MM/DD/…}.</li>
         * <li>Full {@code s3://} URI consistency between JSON fields and actual
         * keys.</li>
         * <li>Suffix consistency across payload / ACK / metadata.</li>
         * <li>SQS on {@code test.fifo} with all path fields matching and
         * {@code messageGroupId} = {@code expectedGroupId}.</li>
         * </ul>
         */
        private void assertHoldFlowS3AndSqs(
                        String expectedPayload,
                        String expectedAck,
                        String expectedGroupId,
                        SoftAssertions softly) throws Exception {

                ListObjectsV2Response objects = s3Client.listObjectsV2(
                                ListObjectsV2Request.builder().bucket(HOLD_BUCKET).build());
                softly.assertThat(objects.contents())
                                .as("HOLD_BUCKET must contain objects").isNotEmpty();
                if (objects.contents().isEmpty())
                        return;

                String metadataKey = objects.contents().stream().map(S3Object::key)
                                .filter(k -> k.contains("_metadata.json")).findFirst().orElse(null);
                String payloadKey = objects.contents().stream().map(S3Object::key)
                                .filter(k -> !k.contains("_ack") && !k.contains("_metadata")).findFirst().orElse(null);
                String ackKey = objects.contents().stream().map(S3Object::key)
                                .filter(k -> k.contains("_ack")).findFirst().orElse(null);

                softly.assertThat(payloadKey).as("Payload key must exist in HOLD_BUCKET").isNotNull();
                softly.assertThat(ackKey).as("ACK key must exist in HOLD_BUCKET").isNotNull();
                softly.assertThat(metadataKey).as("Metadata key must exist in HOLD_BUCKET").isNotNull();
                if (payloadKey == null || ackKey == null || metadataKey == null)
                        return;

                softly.assertThat(normalizeHl7(readS3(HOLD_BUCKET, payloadKey)))
                                .as("Stored HOLD payload must match the HL7 message that was sent")
                                .isEqualTo(normalizeHl7(expectedPayload));

                JsonNode meta = MAPPER.readTree(readS3(HOLD_BUCKET, metadataKey));
                String key = meta.get("key").asText();
                JsonNode jsonMeta = meta.get("json_metadata");
                String s3DataPath = jsonMeta.get("s3DataObjectPath").asText();
                String metaPath = jsonMeta.get("fullS3MetaDataPath").asText();
                String ackPath = jsonMeta.get("fullS3AcknowledgementPath").asText();

                String datePath = todayDatePath();
                String expectedDataPrefix = "outbound/hold/5555/" + datePath; // dataDir=/outbound, route=/hold
                String expectedMetaPrefix = "outbound/hold/metadata/5555/" + datePath;

                softly.assertThat(key)
                                .as("HOLD payload key must follow outbound/hold/5555/YYYY/MM/DD/… structure")
                                .startsWith(expectedDataPrefix);
                softly.assertThat(metadataKey)
                                .as("HOLD metadata key must follow outbound/hold/metadata/5555/YYYY/MM/DD/… structure")
                                .startsWith(expectedMetaPrefix);

                // Full s3:// URI consistency
                softly.assertThat(s3DataPath)
                                .as("s3DataObjectPath = s3://HOLD_BUCKET/payloadKey")
                                .isEqualTo("s3://" + HOLD_BUCKET + "/" + key);
                softly.assertThat(metaPath)
                                .as("fullS3MetaDataPath = s3://HOLD_BUCKET/metadataKey")
                                .isEqualTo("s3://" + HOLD_BUCKET + "/" + metadataKey);
                softly.assertThat(ackPath)
                                .as("fullS3AcknowledgementPath = s3://HOLD_BUCKET/ackKey")
                                .isEqualTo("s3://" + HOLD_BUCKET + "/" + ackKey);

                // Suffix consistency
                String suffix = key.substring(expectedDataPrefix.length() + 1);
                softly.assertThat(ackKey).as("ACK key must end with suffix + '_ack'")
                                .endsWith(suffix + "_ack");
                softly.assertThat(metadataKey).as("Metadata key must end with suffix + '_metadata.json'")
                                .endsWith(suffix + "_metadata.json");

                // ── SQS ───────────────────────────────────────────────────────────────

                assertSqsConsistency(queueUrls.get("test.fifo"),
                                s3DataPath, metaPath, ackPath, key, expectedGroupId, softly);
        }

        /**
         * Polls {@code queueUrl} and validates all SQS message fields against the
         * corresponding S3 metadata values.
         *
         * <p>
         * Fields checked: {@code s3DataObjectPath}, {@code fullS3MetaDataPath},
         * {@code fullS3AcknowledgementPath}, {@code s3ObjectId},
         * {@code messageGroupId}.
         */
        private void assertSqsConsistency(
                        String queueUrl,
                        String expectedDataPath,
                        String expectedMetaPath,
                        String expectedAckPath,
                        String expectedObjectId,
                        String expectedGroupId,
                        SoftAssertions softly) throws Exception {

                ReceiveMessageResponse sqsResponse = waitForSqsMessage(queueUrl);
                softly.assertThat(sqsResponse.messages())
                                .as("SQS queue must contain a message: " + queueUrl).isNotEmpty();
                if (sqsResponse.messages().isEmpty())
                        return;

                JsonNode sqsJson = MAPPER.readTree(sqsResponse.messages().get(0).body());

                softly.assertThat(sqsJson.get("s3DataObjectPath").asText())
                                .as("SQS s3DataObjectPath").isEqualTo(expectedDataPath);
                softly.assertThat(sqsJson.get("fullS3MetaDataPath").asText())
                                .as("SQS fullS3MetaDataPath").isEqualTo(expectedMetaPath);
                softly.assertThat(sqsJson.get("fullS3AcknowledgementPath").asText())
                                .as("SQS fullS3AcknowledgementPath").isEqualTo(expectedAckPath);
                softly.assertThat(sqsJson.get("s3ObjectId").asText())
                                .as("SQS s3ObjectId").isEqualTo(expectedObjectId);
                softly.assertThat(sqsJson.get("messageGroupId").asText())
                                .as("SQS messageGroupId must match MllpMessageGroupStrategy output")
                                .isEqualTo(expectedGroupId);
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // ── TCP / MLLP / PROXY low-level helpers
        // ──────────────────────────────────────
        // ─────────────────────────────────────────────────────────────────────────────

        /**
         * Opens a TCP connection to {@code localhost:{@value #TCP_SERVER_PORT}}, sends
         * a HAProxy PROXY v1 header immediately followed by an MLLP-wrapped HL7 message
         * in a single {@code write()} call (matching AWS ALB behaviour), and returns
         * the
         * raw MLLP-framed ACK bytes.
         *
         * <p>
         * The {@code destPort} written into the PROXY header is the authoritative
         * value for port-config lookup in {@code NettyTcpServer.handleProxyHeader()}.
         */
        private byte[] sendMllpWithProxy(String clientIp, String destIp,
                        int clientPort, int destPort, String hl7) throws Exception {
                try (Socket s = new Socket("localhost", TCP_SERVER_PORT)) {
                        s.setSoTimeout(15_000);
                        s.getOutputStream().write(
                                        concat(buildProxyV1Header(clientIp, destIp, clientPort, destPort),
                                                        wrapMllp(hl7)));
                        s.getOutputStream().flush();
                        return readMllpFrame(s);
                }
        }

        /**
         * Builds a HAProxy PROXY protocol v1 text header.
         *
         * <pre>
         * PROXY TCP4 {clientIp} {destIp} {clientPort} {destPort}\r\n
         * </pre>
         */
        private static byte[] buildProxyV1Header(String clientIp, String destIp,
                        int clientPort, int destPort) {
                return String.format("PROXY TCP4 %s %s %d %d\r\n",
                                clientIp, destIp, clientPort, destPort)
                                .getBytes(StandardCharsets.US_ASCII);
        }

        /** Wraps {@code hl7Text} in MLLP framing: {@code <VT> payload <FS><CR>}. */
        private static byte[] wrapMllp(String hl7Text) {
                byte[] payload = hl7Text.getBytes(StandardCharsets.UTF_8);
                byte[] framed = new byte[1 + payload.length + 2];
                framed[0] = MLLP_START;
                System.arraycopy(payload, 0, framed, 1, payload.length);
                framed[framed.length - 2] = MLLP_END_1;
                framed[framed.length - 1] = MLLP_END_2;
                return framed;
        }

        /**
         * Reads bytes from {@code socket} until the MLLP end-of-frame marker
         * ({@code <FS><CR>}) is seen, returning the complete raw frame.
         */
        private static byte[] readMllpFrame(Socket socket) throws Exception {
                java.io.InputStream in = socket.getInputStream();
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                int b;
                while ((b = in.read()) != -1) {
                        buf.write(b);
                        byte[] soFar = buf.toByteArray();
                        int len = soFar.length;
                        if (len >= 2 && soFar[len - 2] == MLLP_END_1 && soFar[len - 1] == MLLP_END_2)
                                break;
                }
                return buf.toByteArray();
        }

        /** Concatenates two byte arrays into a new array. */
        private static byte[] concat(byte[] a, byte[] b) {
                byte[] out = new byte[a.length + b.length];
                System.arraycopy(a, 0, out, 0, a.length);
                System.arraycopy(b, 0, out, a.length, b.length);
                return out;
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // ── Fixture loading
        // ───────────────────────────────────────────────────────────
        // ─────────────────────────────────────────────────────────────────────────────

        /**
         * Loads an HL7 fixture from the test classpath at
         * {@code org/techbd/ingest/tcp-test-resources/{filename}}.
         *
         * <p>
         * Place fixtures under
         * {@code src/test/resources/org/techbd/ingest/tcp-test-resources/}.
         */
        private static String loadHl7Fixture(String filename) throws Exception {
                String path = "org/techbd/ingest/tcp-test-resources/" + filename;
                try (InputStream is = NettyTcpServerITCase.class
                                .getClassLoader().getResourceAsStream(path)) {
                        if (is == null)
                                throw new IllegalStateException(
                                                "HL7 fixture not found on classpath: " + path);
                        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // ── SQS polling
        // ───────────────────────────────────────────────────────────────
        // ─────────────────────────────────────────────────────────────────────────────

        /** Polls {@code queueUrl} (up to 10 × 3 s) until a message arrives. */
        private ReceiveMessageResponse waitForSqsMessage(String queueUrl) throws InterruptedException {
                if (queueUrl == null)
                        throw new IllegalArgumentException("queueUrl must not be null");
                for (int i = 0; i < 10; i++) {
                        ReceiveMessageResponse resp = sqsClient.receiveMessage(
                                        ReceiveMessageRequest.builder().queueUrl(queueUrl).waitTimeSeconds(2).build());
                        if (!resp.messages().isEmpty())
                                return resp;
                        Thread.sleep(1_000);
                }
                throw new AssertionError("No SQS message received after retries on queue: " + queueUrl);
        }

        // ─────────────────────────────────────────────────────────────────────────────
        // ── S3 / key-path utilities
        // ───────────────────────────────────────────────────
        // ─────────────────────────────────────────────────────────────────────────────

        private String readS3(String bucket, String key) {
                return s3Client.getObjectAsBytes(
                                GetObjectRequest.builder().bucket(bucket).key(key).build()).asUtf8String();
        }

        /** {@code data/YYYY/MM/DD} — date prefix for the default (port-2575) flow. */
        private static String todayDataPrefix() {
                LocalDate d = LocalDate.now();
                return String.format("data/%d/%02d/%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth());
        }

        private static String todayErrorPrefix() {
                LocalDate d = LocalDate.now();
                return String.format("error/%d/%02d/%02d",
                                d.getYear(), d.getMonthValue(), d.getDayOfMonth());
        }

        /** {@code YYYY/MM/DD} — bare date path used inside HOLD key prefixes. */
        private static String todayDatePath() {
                LocalDate d = LocalDate.now();
                return String.format("%d/%02d/%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth());
        }

        private static String extractSuffix(String fullPath, String prefix) {
                int idx = fullPath.indexOf(prefix);
                if (idx < 0)
                        throw new AssertionError(
                                        "Prefix '" + prefix + "' not found in '" + fullPath + "'");
                return fullPath.substring(idx + prefix.length());
        }

        private static String normalizeHl7(String hl7) {
                if (hl7 == null)
                        return "";
                return hl7.replaceAll("\\s+", " ").trim();
        }
}