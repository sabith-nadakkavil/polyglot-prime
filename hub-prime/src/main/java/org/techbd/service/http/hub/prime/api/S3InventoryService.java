package org.techbd.service.http.hub.prime.api;

import java.time.LocalDate;
import java.util.List;

public interface S3InventoryService {
    List<S3InventoryRecord> queryS3Inventory(LocalDate start, LocalDate end, int limit);
}
