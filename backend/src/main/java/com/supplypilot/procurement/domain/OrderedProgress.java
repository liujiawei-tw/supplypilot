package com.supplypilot.procurement.domain;

/** The minimal durable distinction needed while an ORDERED PO is followed up or inspected. */
public enum OrderedProgress {
    FOLLOW_UP,
    RECEIVING_INSPECTION,
    LEGACY_UNKNOWN
}
