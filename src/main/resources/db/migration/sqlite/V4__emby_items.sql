-- Emby 媒体库同步快照：Emby 中已存在条目（按番号识别），用于
-- 「已存在于emby」卡片标记与整理弹窗「已存在」分组（emby 已存在但本地未整理）
CREATE TABLE emby_items (
    item_id    VARCHAR(64) PRIMARY KEY,               -- Emby 条目 Id
    number     VARCHAR(64) NOT NULL,                  -- 从文件名/标题识别出的番号（规范化）
    name       TEXT,                                  -- Emby 条目标题
    path       TEXT,                                  -- Emby 条目文件路径
    synced_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_emby_items_number ON emby_items (number);
