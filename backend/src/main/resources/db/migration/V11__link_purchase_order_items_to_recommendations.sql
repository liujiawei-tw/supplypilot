ALTER TABLE purchase_order_items
    ADD CONSTRAINT fk_purchase_order_items_recommendation
        FOREIGN KEY (recommendation_id) REFERENCES purchase_recommendation(id),
    ADD CONSTRAINT fk_purchase_order_items_supplier_recommendation_result
        FOREIGN KEY (supplier_recommendation_result_id) REFERENCES supplier_recommendation_result(id);
