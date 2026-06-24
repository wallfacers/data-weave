-- 新增连接状态字段（datasource connection status tracking）
ALTER TABLE datasources ADD COLUMN IF NOT EXISTS connection_status VARCHAR(32) DEFAULT 'UNKNOWN';

-- 为已有数据设置初始状态（UNKNOWN = 未测试）
UPDATE datasources SET connection_status = 'UNKNOWN' WHERE connection_status IS NULL;
