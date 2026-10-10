package com.supplypilot.recommendation.ranking;

import com.supplypilot.recommendation.domain.DisqualificationReason;
import java.util.Objects;

public record SupplierConstraintEvaluation(
        boolean qualified, DisqualificationReason disqualificationReason) {

    public SupplierConstraintEvaluation {
        if (qualified && disqualificationReason != null) {
            throw new IllegalArgumentException("Qualified evaluation must not have a disqualification reason");
        }
        if (!qualified) {
            Objects.requireNonNull(disqualificationReason, "Disqualified evaluation must have a reason");
        }
    }

    public static SupplierConstraintEvaluation qualifiedResult() {
        return new SupplierConstraintEvaluation(true, null);
    }

    public static SupplierConstraintEvaluation disqualified(DisqualificationReason reason) {
        return new SupplierConstraintEvaluation(false, reason);
    }
}
