-- Step 3: Claim-Check Pattern (Data & Storage Architecture)
-- Add storage_key column referencing external payload storage
-- Allow data column to be NULL to eliminate TOAST table bloat for diagrams and versions

ALTER TABLE diagrams ADD COLUMN IF NOT EXISTS storage_key VARCHAR(255);
ALTER TABLE diagrams ALTER COLUMN data DROP NOT NULL;

ALTER TABLE diagram_versions ADD COLUMN IF NOT EXISTS storage_key VARCHAR(255);
ALTER TABLE diagram_versions ALTER COLUMN data DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_diagrams_storage_key ON diagrams(storage_key) WHERE storage_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_diagram_versions_storage_key ON diagram_versions(storage_key) WHERE storage_key IS NOT NULL;
