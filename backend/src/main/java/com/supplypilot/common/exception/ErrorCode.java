package com.supplypilot.common.exception;

public enum ErrorCode {

    MATERIAL_NOT_FOUND("Material was not found."),
    SUPPLIER_NOT_FOUND("Supplier was not found."),
    SUPPLIER_MATERIAL_NOT_FOUND("Supplier material was not found."),
    DEMAND_NOT_FOUND("Demand was not found."),
    INVENTORY_NOT_FOUND("Inventory was not found."),
    PURCHASE_ORDER_NOT_FOUND("Purchase order was not found."),
    RECOMMENDATION_NOT_FOUND("Recommendation was not found."),
    DOCUMENT_NOT_FOUND("Document was not found."),
    USER_NOT_FOUND("User was not found."),
    INVENTORY_IMPORT_BATCH_NOT_FOUND("Inventory import batch was not found."),

    MATERIAL_INACTIVE("Material is inactive."),
    SUPPLIER_INACTIVE("Supplier is inactive."),
    SUPPLIER_MATERIAL_INACTIVE("Supplier material is inactive."),
    USER_INACTIVE("User is inactive."),
    DEMAND_NOT_ACTIVE("Demand is not active."),
    PO_NOT_EDITABLE("Purchase order is not editable in its current status."),
    INVALID_PO_STATUS("Purchase order must be ORDERED before receiving."),
    INVALID_PO_STATUS_TRANSITION("Purchase order status transition is not allowed."),
    DUPLICATE_RECEIVING("Purchase order has already been received."),

    VALIDATION_FAILED("Request validation failed."),
    INVALID_QUANTITY("Quantity is invalid."),
    NEGATIVE_QUANTITY("Quantity cannot be negative."),
    INVALID_DATE_RANGE("Date range is invalid."),
    UNIT_MISMATCH("Unit does not match."),
    DUPLICATE_MATERIAL("Material is duplicated."),
    DUPLICATE_CODE("Code already exists."),
    DUPLICATE_USERNAME("Username already exists."),
    EXPECTED_DELIVERY_DATE_REQUIRED("Expected delivery date is required."),
    REJECT_REASON_REQUIRED("Reject reason is required."),
    SUPPLIER_OVERRIDE_REASON_REQUIRED("Supplier override reason is required."),
    QUANTITY_OVERRIDE_REASON_REQUIRED("Quantity override reason is required."),

    INSUFFICIENT_INVENTORY("Inventory is insufficient."),
    INVENTORY_IMPORT_NOT_VALIDATED("Inventory import has not been validated."),
    INVENTORY_IMPORT_ALREADY_CONFIRMED("Inventory import has already been confirmed."),
    INVALID_FILE_FORMAT("File format is invalid."),
    FILE_EMPTY("File is empty."),
    FILE_TOO_LARGE("File is too large."),
    CONCURRENT_MODIFICATION("The resource was modified by another request. Refresh and try again."),

    INVALID_RECOMMENDATION_REFERENCE("Recommendation reference is invalid."),
    INVALID_SUPPLIER_RECOMMENDATION_REFERENCE("Supplier recommendation reference is invalid."),

    DATA_INTEGRITY_VIOLATION("The request conflicts with existing data."),
    INTERNAL_SERVER_ERROR("An unexpected error occurred."),

    AI_PROVIDER_UNAVAILABLE("AI provider is unavailable."),
    AI_TIMEOUT("AI provider timed out."),
    AI_INVALID_RESPONSE("AI provider returned an invalid response."),

    UNAUTHENTICATED("Authentication is required."),
    ACCESS_DENIED("Access is denied.");

    private final String defaultMessage;

    ErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
