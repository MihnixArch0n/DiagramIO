-- Step 3: Claim-Check Pattern (Data & Storage Architecture)
-- Add storage_key column referencing external payload storage
-- Allow data column to be NULL to eliminate TOAST table bloat for diagrams and versions

ALTER TABLE diagrams ADD COLUMN IF NOT EXISTS storage_key VARCHAR(255);
ALTER TABLE diagrams ALTER COLUMN data DROP NOT NULL;

ALTER TABLE diagram_versions ADD COLUMN IF NOT EXISTS storage_key VARCHAR(255);
ALTER TABLE diagram_versions ALTER COLUMN data DROP NOT NULL;


