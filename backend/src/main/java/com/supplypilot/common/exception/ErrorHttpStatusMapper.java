package com.supplypilot.common.exception;

import org.springframework.http.HttpStatus;

final class ErrorHttpStatusMapper {

    private ErrorHttpStatusMapper() {
    }

    static HttpStatus toHttpStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case MATERIAL_NOT_FOUND,
                    SUPPLIER_NOT_FOUND,
                    SUPPLIER_MATERIAL_NOT_FOUND,
                    DEMAND_NOT_FOUND,
                    INVENTORY_NOT_FOUND,
                    PURCHASE_ORDER_NOT_FOUND,
                    RECOMMENDATION_NOT_FOUND,
                    DOCUMENT_NOT_FOUND,
                    USER_NOT_FOUND,
                    INVENTORY_IMPORT_BATCH_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DUPLICATE_MATERIAL,
                    DUPLICATE_CODE,
                    PO_NOT_EDITABLE,
                    INVALID_PO_STATUS_TRANSITION,
                    INVENTORY_IMPORT_NOT_VALIDATED,
                    INVENTORY_IMPORT_ALREADY_CONFIRMED,
                    CONCURRENT_MODIFICATION,
                    DATA_INTEGRITY_VIOLATION -> HttpStatus.CONFLICT;
            case AI_PROVIDER_UNAVAILABLE, AI_TIMEOUT, AI_INVALID_RESPONSE -> HttpStatus.SERVICE_UNAVAILABLE;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case INTERNAL_SERVER_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
