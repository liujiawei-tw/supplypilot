package com.supplypilot.inventory.importing.domain;

import java.math.BigDecimal;
import java.util.Objects;

import com.supplypilot.material.domain.Material;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "inventory_import_items")
public class InventoryImportItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private InventoryImportBatch batch;

    @Column(name = "csv_row_number", nullable = false)
    private int rowNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "material_id")
    private Material material;

    @Column(name = "material_code_snapshot", length = 255)
    private String materialCodeSnapshot;

    @Column(precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(length = 20)
    private String unit;

    @Column(nullable = false)
    private boolean valid;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    protected InventoryImportItem() {
    }

    public static InventoryImportItem from(InventoryImportBatch batch, ValidatedInventoryImportRow row) {
        Objects.requireNonNull(batch, "Batch must not be null");
        Objects.requireNonNull(row, "Validated row must not be null");
        InventoryImportItem item = new InventoryImportItem();
        item.batch = batch;
        item.rowNumber = row.rowNumber();
        item.material = row.material();
        item.materialCodeSnapshot = truncate(row.materialCodeSnapshot(), 255);
        item.quantity = row.quantity();
        item.unit = truncate(row.unit(), 20);
        item.valid = row.valid();
        item.errorCode = row.errorCode() == null ? null : row.errorCode().name();
        item.errorMessage = truncate(row.errorMessage(), 500);
        return item;
    }

    private static String truncate(String value, int length) {
        return value == null ? null : value.substring(0, Math.min(length, value.length()));
    }

    public Long getId() { return id; }
    public InventoryImportBatch getBatch() { return batch; }
    public int getRowNumber() { return rowNumber; }
    public Material getMaterial() { return material; }
    public String getMaterialCodeSnapshot() { return materialCodeSnapshot; }
    public BigDecimal getQuantity() { return quantity; }
    public String getUnit() { return unit; }
    public boolean isValid() { return valid; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
}
