-- 封面候选列表（复刻 JavSP covers 机制）：
-- cover_candidates 为按优先级排序的 JSON 数组 [{"url":"...","source":"javbus"}]，
-- 下载失败/图片无效自动换下一候选；javdb 封面（站点水印）固定沉底
ALTER TABLE movies ADD COLUMN cover_candidates TEXT;
-- 封面实际生效来源（下载沿候选成功后回写）
ALTER TABLE movies ADD COLUMN cover_source VARCHAR(32);
-- 剧照来源数据源 id（下载过盾路由用）
ALTER TABLE movies ADD COLUMN preview_source VARCHAR(32);
