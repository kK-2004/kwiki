-- 修正历史数据：管理端取消子任务后执行器直接退出、批次从未结算，批次停在 QUEUED/RUNNING。
-- 只处理子任务已全部结束的批次，结算规则与 JdbcGraphBuildRepository.settleBatch 一致。
UPDATE graph_build_batch b
SET b.state = CASE
    WHEN NOT EXISTS (SELECT 1 FROM graph_build_run r
                     WHERE r.batch_id = b.id AND r.state IN ('FAILED', 'CANCELLED', 'STALE'))
        THEN 'READY'
    WHEN EXISTS (SELECT 1 FROM graph_build_run r
                 WHERE r.batch_id = b.id AND r.state IN ('READY', 'PUBLISHED'))
        THEN 'PARTIAL_FAILED'
    WHEN NOT EXISTS (SELECT 1 FROM graph_build_run r
                     WHERE r.batch_id = b.id AND r.state IN ('FAILED', 'STALE'))
        THEN 'CANCELLED'
    ELSE 'FAILED'
END
WHERE b.state IN ('QUEUED', 'RUNNING')
  AND EXISTS (SELECT 1 FROM graph_build_run r WHERE r.batch_id = b.id)
  AND NOT EXISTS (SELECT 1 FROM graph_build_run r
                  WHERE r.batch_id = b.id
                    AND r.state NOT IN ('READY', 'PUBLISHED', 'FAILED', 'CANCELLED', 'STALE'));

