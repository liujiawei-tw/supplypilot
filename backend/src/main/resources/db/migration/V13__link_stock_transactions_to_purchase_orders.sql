ALTER TABLE stock_transactions
    ADD CONSTRAINT fk_stock_transactions_purchase_order
        FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id);
