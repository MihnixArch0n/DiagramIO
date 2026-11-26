-- Bước 1: Tối ưu hóa chỉ mục CSDL (Member 1 - Data Architecture & Tuning)

-- 1. Kích hoạt extension pg_trgm cho tìm kiếm chuỗi con (substring search)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 2. GIN Trigram index trên title để tăng tốc ILIKE '%...%' từ Seq Scan sang Bitmap Index Scan
CREATE INDEX IF NOT EXISTS idx_diagrams_title_trgm ON diagrams USING gin (title gin_trgm_ops);

-- 3. B-Tree index trên khóa ngoại diagram_shares(diagram_id) tối ưu hóa truy vấn chia sẻ và cascade
CREATE INDEX IF NOT EXISTS idx_diagram_shares_diagram_id ON diagram_shares(diagram_id);

-- 4. Composite index trên diagrams(owner_id, updated_at DESC) tối ưu hóa lọc sơ đồ người dùng và sắp xếp phân trang
CREATE INDEX IF NOT EXISTS idx_diagrams_owner_updated ON diagrams(owner_id, updated_at DESC);

-- 5. Composite index trên diagrams(is_public, updated_at DESC) tối ưu hóa API danh sách công khai
CREATE INDEX IF NOT EXISTS idx_diagrams_public_updated ON diagrams(is_public, updated_at DESC);
