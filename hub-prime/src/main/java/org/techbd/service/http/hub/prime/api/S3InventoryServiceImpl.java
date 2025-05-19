package org.techbd.service.http.hub.prime.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.Datum;
import software.amazon.awssdk.services.athena.model.GetQueryExecutionRequest;
import software.amazon.awssdk.services.athena.model.GetQueryExecutionResponse;
import software.amazon.awssdk.services.athena.model.GetQueryResultsRequest;
import software.amazon.awssdk.services.athena.model.GetQueryResultsResponse;
import software.amazon.awssdk.services.athena.model.QueryExecutionContext;
import software.amazon.awssdk.services.athena.model.QueryExecutionState;
import software.amazon.awssdk.services.athena.model.ResultConfiguration;
import software.amazon.awssdk.services.athena.model.Row;
import software.amazon.awssdk.services.athena.model.StartQueryExecutionRequest;

@Service
public class S3InventoryServiceImpl implements S3InventoryService {

    private static final Logger logger = LoggerFactory.getLogger(S3InventoryServiceImpl.class);
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MS = 1000;
    private static final int MAX_POLLING_ATTEMPTS = 100;  // To prevent infinite loops
    
    private final AthenaClient athenaClient;

    @Value("${athena.database}")
    private String database;

    @Value("${athena.outputLocation}")
    private String outputLocation;

    @Value("${athena.inventory.table}")
    private String inventoryTable;
    
    @Value("${athena.metadata.table}")
    private String metadataTable;

    public S3InventoryServiceImpl(AthenaClient athenaClient) {
        this.athenaClient = athenaClient;
    }

    @Override
    @Retryable(value = SdkException.class, maxAttempts = MAX_RETRY_ATTEMPTS, backoff = @Backoff(delay = RETRY_DELAY_MS))
    public List<S3InventoryRecord> queryS3Inventory(LocalDate start, LocalDate end, int limit) {
        logger.debug("Querying S3 inventory between {} and {} with limit {}", start, end, limit);
        
        String query = buildQuery(start, end, limit);
        logger.debug("Executing query: {}", query);

        String queryExecutionId = submitAthenaQuery(query);
        waitForQueryToComplete(queryExecutionId);
        return processResultRows(queryExecutionId);
    }

    private String buildQuery(LocalDate start, LocalDate end, int limit) {
        return String.format(
            "SELECT inv.*,\n" +
            "  jm.json_metadata.interactionId,\n" +
            "  jm.json_metadata.fileName,\n" +
            "  jm.json_metadata.msgType,\n" +
            "  jm.json_metadata.uploadDate,\n" +
            "  jm.json_metadata.fileSize,\n" +
            "  jm.json_metadata.sourceSystem,\n" +
            "  jm.json_metadata.s3ObjectPath,\n" +
            "  jm.json_metadata.tenantId,\n" +
            "  jm.json_metadata.timestamp\n" +
            "FROM %s inv\n" +
            "    JOIN %s jm\n" +
            "        ON inv.key = jm.key\n" +
            "WHERE inv.last_modified_date BETWEEN DATE('%s') AND DATE('%s') ORDER BY inv.last_modified_date DESC LIMIT %d", 
            inventoryTable, metadataTable, start, end, limit
        );
    }

    private String submitAthenaQuery(String query) {
        try {
            QueryExecutionContext context = QueryExecutionContext.builder()
                .database(database)
                .build();
            
            ResultConfiguration resultConfig = ResultConfiguration.builder()
                .outputLocation(outputLocation)
                .build();

            StartQueryExecutionRequest request = StartQueryExecutionRequest.builder()
                .queryString(query)
                .queryExecutionContext(context)
                .resultConfiguration(resultConfig)
                .build();

            return athenaClient.startQueryExecution(request).queryExecutionId();
        } catch (SdkException e) {
            logger.error("Failed to submit Athena query", e);
            throw e;
        }
    }

    private void waitForQueryToComplete(String queryExecutionId) {
        GetQueryExecutionRequest getRequest = GetQueryExecutionRequest.builder()
            .queryExecutionId(queryExecutionId)
            .build();
    
        try {
            int attempts = 0;
            while (attempts < MAX_POLLING_ATTEMPTS) {
                GetQueryExecutionResponse response = athenaClient.getQueryExecution(getRequest);
                QueryExecutionState state = QueryExecutionState.fromValue(
                    response.queryExecution().status().state().toString()
                );
    
                switch (state) {
                    case SUCCEEDED:
                        logger.debug("Query completed successfully");
                        return;
                    case FAILED:
                        String reason = response.queryExecution().status().stateChangeReason();
                        logger.error("Query failed: {}", reason);
                        throw new RuntimeException("Query failed: " + reason);
                    case CANCELLED:
                        logger.error("Query was cancelled");
                        throw new RuntimeException("Query was cancelled");
                    default:
                        // Query is still running
                        attempts++;
                        try {
                            Thread.sleep(RETRY_DELAY_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Thread interrupted while waiting for query completion", e);
                        }
                }
            }
            throw new RuntimeException("Query timed out after " + MAX_POLLING_ATTEMPTS + " polling attempts");
        } catch (SdkException e) {
            logger.error("Error while checking query status", e);
            throw e;
        }
    }

    private List<S3InventoryRecord> processResultRows(String queryExecutionId) {
        try {
            GetQueryResultsRequest resultRequest = GetQueryResultsRequest.builder()
                .queryExecutionId(queryExecutionId)
                .build();

            List<S3InventoryRecord> results = new ArrayList<>();
            GetQueryResultsResponse resultResponse = athenaClient.getQueryResults(resultRequest);
            List<Row> rows = resultResponse.resultSet().rows();

            // Skip the header row
            for (int i = 1; i < rows.size(); i++) {
                try {
                    List<Datum> data = rows.get(i).data();
                    results.add(mapRowToRecord(data));
                } catch (Exception e) {
                    logger.error("Error processing row {}: {}", i, e.getMessage());
                    // Continue processing other rows
                }
            }

            logger.debug("Processed {} records", results.size());
            return results;
        } catch (SdkException e) {
            logger.error("Failed to process query results", e);
            throw e;
        }
    }

    private S3InventoryRecord mapRowToRecord(List<Datum> data) {
        return new S3InventoryRecord(
            getValueOrDefault(data.get(0)),  // key
            getValueOrDefault(data.get(1)),  // bucket
            getValueOrDefault(data.get(2)),  // version_id
            Boolean.parseBoolean(getValueOrDefault(data.get(3))),  // is_latest
            Boolean.parseBoolean(getValueOrDefault(data.get(4))),  // is_delete_marker
            getValueOrDefault(data.get(5)),  // size
            getValueOrDefault(data.get(6)),  // last_modified_date
            getValueOrDefault(data.get(7)),  // e_tag
            getValueOrDefault(data.get(8)),  // storage_class
            getValueOrDefault(data.get(9)),  // multipart_upload_id
            getValueOrDefault(data.get(10)), // replication_status
            getValueOrDefault(data.get(11)), // encryption_status
            getValueOrDefault(data.get(12)), // object_lock_retain_until_date
            getValueOrDefault(data.get(13)), // object_lock_mode
            getValueOrDefault(data.get(14)), // object_lock_legal_hold_status
            getValueOrDefault(data.get(15)), // intelligent_tiering_access_tier
            getValueOrDefault(data.get(16)), // bucket_key_status
            getValueOrDefault(data.get(17)), // checksum_algorithm
            getValueOrDefault(data.get(18)), // interactionId
            getValueOrDefault(data.get(19)), // fileName
            getValueOrDefault(data.get(20)), // msgType
            getValueOrDefault(data.get(21)), // uploadDate
            getValueOrDefault(data.get(22))  // fileSize
        );
    }

    private String getValueOrDefault(Datum datum) {
        return datum != null && datum.varCharValue() != null ? datum.varCharValue() : "";
    }
}
