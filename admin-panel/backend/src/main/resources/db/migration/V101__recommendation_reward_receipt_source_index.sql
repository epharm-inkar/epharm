-- Keep the source/physical/fiscal OR lookup indexable as the financial receipt journal grows.
CREATE INDEX ix_reward_receipts_source_sale ON recommendation_reward_receipts(source_sale_id);
