package com.supplypilot.demand.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.domain.Material;
import com.supplypilot.user.domain.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "demands")
public class Demand {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "material_id", nullable = false) private Material material;
    @Column(nullable = false, precision = 19, scale = 4) private BigDecimal quantity;
    @Column(name = "required_date", nullable = false) private LocalDate requiredDate;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private DemandStatus status;
    @Column(length = 500) private String note;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by", nullable = false) private User createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected Demand() { }

    public static Demand create(Material material, BigDecimal quantity, LocalDate requiredDate, String note, User createdBy, Instant now) {
        Demand demand = new Demand();
        demand.material = Objects.requireNonNull(material, "Material must not be null");
        demand.quantity = validQuantity(quantity);
        demand.requiredDate = Objects.requireNonNull(requiredDate, "Required date must not be null");
        demand.note = note;
        demand.createdBy = Objects.requireNonNull(createdBy, "Created by must not be null");
        demand.status = DemandStatus.ACTIVE;
        demand.createdAt = Objects.requireNonNull(now, "Timestamp must not be null");
        demand.updatedAt = now;
        return demand;
    }

    public void update(BigDecimal newQuantity, LocalDate newRequiredDate, String newNote, boolean noteProvided, LocalDate today, Instant now) {
        Objects.requireNonNull(today, "Today must not be null");
        Objects.requireNonNull(now, "Timestamp must not be null");
        requireEditable(today);
        BigDecimal proposedQuantity = newQuantity == null ? quantity : validQuantity(newQuantity);
        LocalDate proposedRequiredDate = newRequiredDate == null ? requiredDate : newRequiredDate;
        if (proposedRequiredDate.isBefore(today)) throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);

        boolean modified = proposedQuantity.compareTo(quantity) != 0
                || !proposedRequiredDate.equals(requiredDate)
                || (noteProvided && !Objects.equals(newNote, note));
        if (modified) {
            quantity = proposedQuantity;
            requiredDate = proposedRequiredDate;
            if (noteProvided) note = newNote;
            updatedAt = now;
        }
    }

    public void cancel(Instant now) {
        Objects.requireNonNull(now, "Timestamp must not be null");
        if (status != DemandStatus.ACTIVE) throw new BusinessException(ErrorCode.DEMAND_NOT_ACTIVE);
        status = DemandStatus.CANCELLED;
        updatedAt = now;
    }

    private void requireEditable(LocalDate today) {
        if (status != DemandStatus.ACTIVE) throw new BusinessException(ErrorCode.DEMAND_NOT_ACTIVE);
        if (requiredDate.isBefore(today)) throw new BusinessException(ErrorCode.INVALID_DATE_RANGE);
    }

    private static BigDecimal validQuantity(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(ErrorCode.INVALID_QUANTITY);
        return value;
    }

    public Long getId() { return id; }
    public Material getMaterial() { return material; }
    public BigDecimal getQuantity() { return quantity; }
    public LocalDate getRequiredDate() { return requiredDate; }
    public DemandStatus getStatus() { return status; }
    public String getNote() { return note; }
    public User getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
