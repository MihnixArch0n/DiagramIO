-- Measure detailed relation, index, and TOAST table storage sizes
SELECT
    relname AS table_name,
    pg_size_pretty(pg_relation_size(relid)) AS table_size,
    pg_size_pretty(pg_total_relation_size(relid) - pg_relation_size(relid)) AS toast_and_indexes_size,
    pg_size_pretty(pg_total_relation_size(relid)) AS total_size
FROM pg_catalog.pg_statio_user_tables
WHERE relname IN ('diagrams', 'diagram_versions', 'diagram_shares', 'users')
ORDER BY pg_total_relation_size(relid) DESC;
