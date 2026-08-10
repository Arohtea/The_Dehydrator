-- 生产环境在切换到 validate 或手工迁移模式前执行。
ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS deleting boolean;

UPDATE documents
SET deleting = false
WHERE deleting IS NULL;

ALTER TABLE documents
    ALTER COLUMN deleting SET DEFAULT false;

ALTER TABLE documents
    ALTER COLUMN deleting SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_analysis_tasks_document_status
    ON analysis_tasks (document_id, status);
