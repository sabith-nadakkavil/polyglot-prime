package org.techbd.ingest.integrationtests.soap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.techbd.ingest.integrationtests.util.SoapTestFixtures.extractXPath;
import static org.techbd.ingest.integrationtests.util.SoapTestFixtures.loadFixture;

import java.nio.charset.StandardCharsets;

import javax.xml.soap.SOAPConstants;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.techbd.ingest.commons.Constants;
import org.techbd.ingest.integrationtests.base.BaseIntegrationTest;
import org.techbd.ingest.integrationtests.base.IngestionAssertionHelper;
import org.techbd.ingest.integrationtests.base.IngestionAssertionHelper.FlowAssertionParams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * Full-stack integration tests for SOAP endpoints.
 *
 * <p>LocalStack (S3 + SQS), bucket/queue creation, and port-config upload are
 * all handled <em>once</em> by {@link BaseIntegrationTest}. Between every test
 * method {@code BaseIntegrationTest.cleanS3AndSqsState()} purges data buckets
 * and SQS queues, so each test starts with a clean slate.
 *
 * <p>All S3 / SQS assertions are delegated to {@link IngestionAssertionHelper},
 * which is parameterised per test via {@link FlowAssertionParams} builders.
 * This keeps the test methods focused on the HTTP round-trip and removes all
 * duplicated assertion logic.
 */
class SoapWsEndPointITCase extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ═══════════════════════════════════════════════════════════════════════════
    // Shared helper instance
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Lazily-created helper. {@code s3Client} and {@code sqsClient} are static
     * fields on {@link BaseIntegrationTest} and are ready after
     * {@code setupLocalStackEnvironment()}.
     */
    private IngestionAssertionHelper assertionHelper() {
        return new IngestionAssertionHelper(s3Client, sqsClient);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Diagnostic tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DIAG: Health check returns 200")
    void shouldReturnHealthyStatus() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("DIAG: Port 9000 config is present in list.json on S3")
    void shouldLoadHttpPortConfiguration() throws Exception {
        String json = new String(
                s3Client.getObject(GetObjectRequest.builder()
                        .bucket(PORT_CONFIG_BUCKET)
                        .key(PORT_CONFIG_KEY)
                        .build())
                        .readAllBytes(),
                StandardCharsets.UTF_8);

        JsonNode root = MAPPER.readTree(json);
        for (JsonNode node : root) {
            if (node.has("port") && node.get("port").asInt() == 9000) {
                assertThat(node.get("protocol").asText()).isEqualTo("HTTP");
                return;
            }
        }
        throw new AssertionError("Port 9000 config not found in list.json");
    }

    @Test
    @DisplayName("DIAG: messageDispatcher beans are registered")
    void shouldRegisterMessageDispatcherBeans() {
        long count = java.util.Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> name.contains("messageDispatcher"))
                .peek(System.out::println)
                .count();
        assertThat(count).isPositive();
    }

    @Test
    @DisplayName("DIAG: Servlet registration beans are mapped")
    void shouldMapServletRegistrations() {
        var beans = context.getBeansOfType(
                org.springframework.boot.web.servlet.ServletRegistrationBean.class);
        assertThat(beans).isNotEmpty();
        beans.forEach((name, bean) -> System.out.println(name + " -> " + bean.getUrlMappings()));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PIX Add
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * IT: PIX ADD SOAP 1.2 — default flow (port 9000).
     *
     * <p>Port config:
     * <pre>{ "port": 9000, "protocol": "HTTP", "execType": "sync", "trafficTo": "util" }</pre>
     *
     * <p>No custom route / dataDir / metadataDir → default key structure:
     * <ul>
     *   <li>Payload:  {@code data/YYYY/MM/DD/{id}_{ts}}</li>
     *   <li>ACK:      {@code data/YYYY/MM/DD/{id}_{ts}_ack}</li>
     *   <li>Metadata: {@code metadata/YYYY/MM/DD/{id}_{ts}_metadata.json}</li>
     * </ul>
     */
    @Test
    @DisplayName("IT: PIX-ADD SOAP 1.2 — full flow (HTTP + S3 + SQS)")
    void shouldProcessPixAddSoap12_andPersistToS3AndSqs() throws Exception {
        String request = loadFixture("pix-add-request_1_2.txt");
        String expectedAck = loadFixture("pix-add-response_1_2.txt");
        SoftAssertions softly = new SoftAssertions();

        String response = sendSoapRequest(request, SOAPConstants.SOAP_1_2_PROTOCOL, "9000", softly);
        softly.assertThat(response).as("Response contains PIX ACK").contains("MCCI_IN000002UV01");
        assertSoap12Envelope(response, softly);

        assertionHelper().assertDefaultFlow(defaultFlowParams(request, expectedAck, "127.0.0.1_9000"), softly);

        softly.assertAll();
    }

    /**
     * IT: PIX ADD SOAP 1.2 — HOLD flow (port 5555).
     *
     * <p>Port config:
     * <pre>{ "port": 5555, "route": "/hold", "dataDir": "/http",
     *   "metadataDir": "/outbound", "queue": "test.fifo" }</pre>
     */
    @Test
    @DisplayName("IT: PIX-ADD SOAP 1.2 — HOLD flow (HTTP + S3 Hold Bucket + SQS)")
    void shouldProcessPixAddSoap12_inHoldFlow_andPersistToS3AndSqs() throws Exception {
        String request = loadFixture("pix-add-request_1_2.txt");
        String expectedAck = loadFixture("pix-add-response_1_2.txt");
        SoftAssertions softly = new SoftAssertions();

        String response = sendSoapRequest(request, SOAPConstants.SOAP_1_2_PROTOCOL, "5555", softly);
        softly.assertThat(response).as("Response contains PIX ACK").contains("MCCI_IN000002UV01");
        assertSoap12Envelope(response, softly);

        // Port 5555 hold: dataDir=/http, metadataDir=/outbound, queue=test.fifo
        assertionHelper().assertHoldFlow(
                holdFlowParams(request, expectedAck, "127.0.0.1_5555",
                        5555, "/http", "/outbound", null, queueUrls.get("test.fifo")),
                softly);

        softly.assertAll();
    }

    @Test
    @DisplayName("IT: PIX-ADD SOAP 1.1 — full flow (HTTP + S3 + SQS)")
    void shouldProcessPixAddSoap11_andPersistToS3AndSqs() throws Exception {
        String request = loadFixture("pix-add-request_1_1.txt");
        String expectedAck = loadFixture("pix-add-response_1_1.txt");
        SoftAssertions softly = new SoftAssertions();

        String response = sendSoapRequest(request, SOAPConstants.SOAP_1_1_PROTOCOL, "9000", softly);
        softly.assertThat(response).as("Response contains PIX ACK").contains("MCCI_IN000002UV01");
        assertSoap11Envelope(response, softly);

        assertionHelper().assertDefaultFlow(defaultFlowParams(request, expectedAck, "127.0.0.1_9000"), softly);

        softly.assertAll();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PIX Merge
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("IT: PIX-MERGE SOAP 1.2 — full flow (HTTP + S3 + SQS)")
    void shouldProcessPixMergeSoap12_andPersistToS3AndSqs() throws Exception {
        String request = loadFixture("pix-merge-request_1_2.txt");
        String expectedAck = loadFixture("pix-merge-response_1_2.txt");
        SoftAssertions softly = new SoftAssertions();

        String response = sendSoapRequest(request, SOAPConstants.SOAP_1_2_PROTOCOL, "9000", softly);
        softly.assertThat(response).as("Response contains PIX ACK").contains("MCCI_IN000002UV01");
        assertSoap12Envelope(response, softly);

        assertionHelper().assertDefaultFlow(defaultFlowParams(request, expectedAck, "127.0.0.1_9000"), softly);

        softly.assertAll();
    }

    @Test
    @DisplayName("IT: PIX-MERGE SOAP 1.1 — full flow (HTTP + S3 + SQS)")
    void shouldProcessPixMergeSoap11_andPersistToS3AndSqs() throws Exception {
        String request = loadFixture("pix-merge-request_1_1.txt");
        String expectedAck = loadFixture("pix-merge-response_1_1.txt");
        SoftAssertions softly = new SoftAssertions();

        String response = sendSoapRequest(request, SOAPConstants.SOAP_1_1_PROTOCOL, "9000", softly);
        softly.assertThat(response).as("Response contains MERGE-12345").contains("MERGE-12345");
        assertSoap11Envelope(response, softly);

        assertionHelper().assertDefaultFlow(defaultFlowParams(request, expectedAck, "127.0.0.1_9000"), softly);

        softly.assertAll();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PIX Update
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("IT: PIX-UPDATE SOAP 1.2 — full flow (HTTP + S3 + SQS)")
    void shouldProcessPixUpdateSoap12_andPersistToS3AndSqs() throws Exception {
        String request = loadFixture("pix-update-request_1_2.txt");
        String expectedAck = loadFixture("pix-update-response_1_2.txt");
        SoftAssertions softly = new SoftAssertions();

        String response = sendSoapRequest(request, SOAPConstants.SOAP_1_2_PROTOCOL, "9000", softly);
        softly.assertThat(response).as("Response contains PIX ACK").contains("MCCI_IN000002UV01");
        assertSoap12Envelope(response, softly);

        assertionHelper().assertDefaultFlow(defaultFlowParams(request, expectedAck, "127.0.0.1_9000"), softly);

        softly.assertAll();
    }

    @Test
    @DisplayName("IT: PIX-UPDATE SOAP 1.1 — full flow (HTTP + S3 + SQS)")
    void shouldProcessPixUpdateSoap11_andPersistToS3AndSqs() throws Exception {
        String request = loadFixture("pix-update-request_1_1.txt");
        String expectedAck = loadFixture("pix-update-response_1_1.txt");
        SoftAssertions softly = new SoftAssertions();

        String response = sendSoapRequest(request, SOAPConstants.SOAP_1_1_PROTOCOL, "9000", softly);
        softly.assertThat(response).as("Response contains fallback message ID")
                .contains("unknown-incoming-message-id");
        assertSoap11Envelope(response, softly);

        assertionHelper().assertDefaultFlow(defaultFlowParams(request, expectedAck, "127.0.0.1_9000"), softly);

        softly.assertAll();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PNR
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("IT: PNR SOAP 1.1 — full flow (HTTP + S3 + SQS)")
    void shouldProcessPnrSoap11_andPersistToS3AndSqs() throws Exception {
        String request = loadFixture("pnr-request_1_1.txt");
        SoftAssertions softly = new SoftAssertions();

        String response = sendSoapRequest(request, SOAPConstants.SOAP_1_1_PROTOCOL, "9000", softly);
        softly.assertThat(response).as("Response contains RegistryResponse").contains("RegistryResponse");
        softly.assertThat(response).as("Response contains Success status").contains("ResponseStatusType:Success");
        assertSoap11Envelope(response, softly);

        // PNR has no ACK fixture — ackExpected=false skips ACK-specific assertions
        assertionHelper().assertDefaultFlow(
                defaultFlowParams(request, null, "127.0.0.1_9000", false),
                softly);

        softly.assertAll();
    }

    @Test
    @DisplayName("IT: PNR SOAP 1.2 — full flow (HTTP + S3 + SQS)")
    void shouldProcessPnrSoap12_andPersistToS3AndSqs() throws Exception {
        String request = loadFixture("pnr-request_1_2.txt");
        SoftAssertions softly = new SoftAssertions();

        String response = sendSoapRequest(request, SOAPConstants.SOAP_1_2_PROTOCOL, "9000", softly);
        softly.assertThat(response).as("Response contains RegistryResponse").contains("RegistryResponse");
        softly.assertThat(response).as("Response contains Success status").contains("ResponseStatusType:Success");
        assertSoap12Envelope(response, softly);

        assertionHelper().assertDefaultFlow(
                defaultFlowParams(request, null, "127.0.0.1_9000")
                        .toBuilder().ackExpected(false).build(),
                softly);

        softly.assertAll();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PNR MTOM
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("IT: PNR MTOM — RegistryResponse Success returned")
    void shouldProcessPnrMtom_successReturned() throws Exception {
        String request = loadFixture("pnr-xdsb-mtom-request.txt");
        SoftAssertions softly = new SoftAssertions();

        String response = sendMtomRequest(request, "9000", softly);
        softly.assertThat(response).as("Response contains RegistryResponse").contains("RegistryResponse");
        softly.assertThat(response).as("Response contains Success status").contains("ResponseStatusType:Success");

        assertionHelper().assertDefaultFlow(
                defaultFlowParams(request, null, "127.0.0.1_9000")
                        .toBuilder().ackExpected(false).build(),
                softly);

        softly.assertAll();
    }

    @Test
    @DisplayName("IT: PNR MTOM — wsa:RelatesTo echoes MTOM request MessageID")
    void shouldProcessPnrMtom_relatesTo_matchesMtomMessageId() throws Exception {
        String request = loadFixture("pnr-xdsb-mtom-request.txt");
        SoftAssertions softly = new SoftAssertions();

        String response = sendMtomRequest(request, "9000", softly);
        softly.assertThat(response).as("Response echoes MTOM MessageID")
                .contains("f19a3e9e-324d-4c6c-8a96-6747955d86f5");

        assertionHelper().assertDefaultFlow(
                defaultFlowParams(request, null, "127.0.0.1_9000")
                        .toBuilder().ackExpected(false).build(),
                softly);

        softly.assertAll();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FlowAssertionParams factories
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Builds a {@link FlowAssertionParams} for the <em>default</em> two-bucket
     * flow (port 9000, no route/dataDir/metadataDir).
     *
     * <p>ACK XPath assertions compare {@code //sender/device/id/@root} and
     * {@code //receiver/device/id/@root} against the provided ACK fixture.
     *
     * @param payload     the XML sent in the request
     * @param ackFixture  the expected ACK fixture (null to skip ACK XPath check)
     * @param groupId     expected SQS {@code messageGroupId}
     */
    private FlowAssertionParams defaultFlowParams(String payload, String ackFixture, String groupId) {
        return defaultFlowParams(payload, ackFixture, groupId, true);
    }

    private FlowAssertionParams defaultFlowParams(
            String payload, String ackFixture, String groupId, boolean ackExpected) {
        FlowAssertionParams.Builder b = FlowAssertionParams.builder()
                .dataBucket(DEFAULT_DATA_BUCKET)
                .metadataBucket(DEFAULT_METADATA_BUCKET)
                .queueUrl(mainQueueUrl)
                .expectedMessageGroupId(groupId)
                .expectedPayload(payload)
                .payloadNormalizer(IngestionAssertionHelper::normalizeXml)
                .ackExpected(ackExpected);

        if (ackFixture != null) {
            b.ackXPathAssertions((ackXml, softly) -> {
                softly.assertThat(extractXPath(ackXml, "//sender/device/id/@root"))
                        .isEqualTo(extractXPath(ackFixture, "//sender/device/id/@root"));
                softly.assertThat(extractXPath(ackXml, "//receiver/device/id/@root"))
                        .isEqualTo(extractXPath(ackFixture, "//receiver/device/id/@root"));
            });
        }
        return b.build();
    }

    /**
     * Builds a {@link FlowAssertionParams} for a hold-flow scenario.
     *
     * @param payload     raw XML sent
     * @param ackFixture  expected ACK fixture (null to skip ACK XPath check)
     * @param groupId     expected SQS {@code messageGroupId}
     * @param portNum     listening port (used in hold key when no tenant)
     * @param dataDir     {@code dataDir} from port-config (e.g. {@code "/http"})
     * @param metadataDir {@code metadataDir} from port-config (e.g. {@code "/outbound"})
     * @param tenantId    tenant segment ({@code sourceId_msgType}); null if absent
     * @param queueUrl    SQS queue URL for this entry
     */
    private FlowAssertionParams holdFlowParams(
            String payload, String ackFixture, String groupId,
            int portNum, String dataDir, String metadataDir,
            String tenantId, String queueUrl) {

        FlowAssertionParams.Builder b = FlowAssertionParams.builder()
                .dataBucket(HOLD_BUCKET)
                .metadataBucket(null)           // hold: metadata in same bucket as data
                .holdFlow(true)
                .port(portNum)
                .dataDir(dataDir)
                .metadataDir(metadataDir)
                .tenantId(tenantId)
                .queueUrl(queueUrl)
                .expectedMessageGroupId(groupId)
                .expectedPayload(payload)
                .payloadNormalizer(IngestionAssertionHelper::normalizeXml)
                .ackExpected(true);

        if (ackFixture != null) {
            b.ackXPathAssertions((ackXml, softly) -> {
                softly.assertThat(extractXPath(ackXml, "//sender/device/id/@root"))
                        .isEqualTo(extractXPath(ackFixture, "//sender/device/id/@root"));
                softly.assertThat(extractXPath(ackXml, "//receiver/device/id/@root"))
                        .isEqualTo(extractXPath(ackFixture, "//receiver/device/id/@root"));
            });
        }
        return b.build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Envelope assertion helpers (XPath-based, unchanged)
    // ═══════════════════════════════════════════════════════════════════════════

    private void assertSoap12Envelope(String response, SoftAssertions softly) {
        String soap12Uri = "http://www.w3.org/2003/05/soap-envelope";
        softly.assertThat(extractXPath(response, "local-name(/*)"))
                .as("Root element must be 'Envelope'").isEqualTo("Envelope");
        softly.assertThat(extractXPath(response, "namespace-uri(/*)"))
                .as("Envelope must declare SOAP 1.2 namespace").isEqualTo(soap12Uri);
    }

    private void assertSoap11Envelope(String response, SoftAssertions softly) {
        String soap11Uri = "http://schemas.xmlsoap.org/soap/envelope/";
        softly.assertThat(extractXPath(response, "local-name(/*)"))
                .as("Root element must be 'Envelope'").isEqualTo("Envelope");
        softly.assertThat(extractXPath(response, "namespace-uri(/*)"))
                .as("Envelope must declare SOAP 1.1 namespace").isEqualTo(soap11Uri);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SOAP / MTOM request helpers (unchanged)
    // ═══════════════════════════════════════════════════════════════════════════

    private String sendSoapRequest(String xml, String soapProtocol, String forwardedPort,
            SoftAssertions softly) {
        HttpHeaders headers = buildSoapHeaders(soapProtocol, forwardedPort);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/ws",
                new HttpEntity<>(xml, headers),
                String.class);
        softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private String sendMtomRequest(String mtomRaw, String forwardedPort, SoftAssertions softly) {
        String boundary = extractMtomBoundary(mtomRaw);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE,
                "multipart/related; type=\"application/xop+xml\"; boundary=\"" + boundary + "\"; "
                        + "start=\"<request@meditech.com>\"; start-info=\"application/soap+xml\"");
        headers.set(Constants.REQ_X_FORWARDED_PORT, forwardedPort);
        headers.set(Constants.REQ_X_SERVER_IP, "127.0.0.1");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/ws",
                new HttpEntity<>(mtomRaw, headers),
                String.class);
        softly.assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody();
    }

    private HttpHeaders buildSoapHeaders(String soapProtocol, String forwardedPort) {
        HttpHeaders headers = new HttpHeaders();
        if (SOAPConstants.SOAP_1_2_PROTOCOL.equalsIgnoreCase(soapProtocol)) {
            headers.setContentType(MediaType.valueOf("application/soap+xml"));
        } else if (SOAPConstants.SOAP_1_1_PROTOCOL.equalsIgnoreCase(soapProtocol)) {
            headers.setContentType(MediaType.TEXT_XML);
            headers.add("SOAPAction", "");
        } else {
            throw new IllegalArgumentException("Unsupported SOAP protocol: " + soapProtocol);
        }
        headers.set(Constants.REQ_X_SERVER_IP, "127.0.0.1");
        headers.set(Constants.REQ_X_FORWARDED_PORT, forwardedPort);
        return headers;
    }

    private static String extractMtomBoundary(String mtomRaw) {
        for (String line : mtomRaw.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--") && !trimmed.equals("--")) {
                return trimmed.substring(2);
            }
        }
        throw new IllegalArgumentException("Could not extract MTOM boundary from fixture");
    }
}