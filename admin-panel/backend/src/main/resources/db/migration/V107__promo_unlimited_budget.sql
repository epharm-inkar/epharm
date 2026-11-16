-- Existing campaigns remain capped. No campaign is enabled or financially authorized here.
ALTER TABLE promos ADD COLUMN budget_unlimited BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN promos.budget_unlimited IS
    'Explicit uncapped bonus funding mode; numeric budget zero alone never authorizes spending';
