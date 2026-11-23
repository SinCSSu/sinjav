-- 演员管理（头像 + 别名）：设计文档中标记为 V5，但 V5 已被 cover_candidates 占用，故落为 V6。
--
-- updated_at 不设 DB 默认值：存量行由下方 UPDATE 回填，新写入由仓库层显式 SET（与 movies 等表 UPDATE 的写法一致），
-- 与 sqlite 侧脚本保持完全对称。

-- 本地照片相对路径（如 actors/夢乃あいか.jpg）；未抓取为 NULL
ALTER TABLE actresses ADD COLUMN photo_path TEXT;
-- 规范名（用于 Gfriends 匹配与照片文件命名，可与 name 不同）
ALTER TABLE actresses ADD COLUMN alias TEXT;
-- Emby Person Id 缓存（推送头像用）
ALTER TABLE actresses ADD COLUMN emby_person_id TEXT;
-- 更新时间
ALTER TABLE actresses ADD COLUMN updated_at TIMESTAMP;

UPDATE actresses SET updated_at = CURRENT_TIMESTAMP WHERE updated_at IS NULL;

-- 演员别名（同一演员的多种写法，头像匹配时逐一尝试）
CREATE TABLE actress_aliases (
    id         BIGSERIAL PRIMARY KEY,
    actress_id BIGINT NOT NULL,
    alias      TEXT NOT NULL UNIQUE,
    FOREIGN KEY (actress_id) REFERENCES actresses (id) ON DELETE CASCADE
);

CREATE INDEX idx_actress_aliases_actress_id ON actress_aliases (actress_id);

-- Gfriends 头像库索引（Filetree.json 解析结果，name 为 {日文名}.jpg 的键）
CREATE TABLE gfriends_index (
    name TEXT PRIMARY KEY,
    url  TEXT NOT NULL
);
