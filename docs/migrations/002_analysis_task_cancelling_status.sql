-- 终止流程先落库为 CANCELLING，数据库约束必须与代码状态机保持一致。
ALTER TABLE analysis_tasks
    DROP CONSTRAINT IF EXISTS analysis_tasks_status_check;

ALTER TABLE analysis_tasks
    ADD CONSTRAINT analysis_tasks_status_check
    CHECK (status IN (
        'PENDING',
        'PROCESSING',
        'CANCELLING',
        'COMPLETED',
        'FAILED',
        'CANCELLED'
    ));
