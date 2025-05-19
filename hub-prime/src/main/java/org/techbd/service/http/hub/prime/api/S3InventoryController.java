package org.techbd.service.http.hub.prime.api;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/inventory")
public class S3InventoryController {

    private final S3InventoryService inventoryService;

    public S3InventoryController(S3InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping()
    public List<S3InventoryRecord> getInventory(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
        @RequestParam(defaultValue = "10") int limit) {

        if (endDate == null) {
            endDate = LocalDate.now();
        }

        if (startDate == null) {
            startDate = endDate.minusDays(30);
        }

        if (startDate.isAfter(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate must be before endDate");
        }

        return inventoryService.queryS3Inventory(startDate, endDate, limit);
    }
}
