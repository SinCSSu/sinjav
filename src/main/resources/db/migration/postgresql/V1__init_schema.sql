-- sinJav 初始 schema（PostgreSQL 方言）

-- 影片元数据（刮削结果，按番号唯一）
CREATE TABLE movies (
    id            BIGSERIAL PRIMARY KEY,
    number        VARCHAR(64) UNIQUE NOT NULL,          -- 番号（规范化后，如 ABP-123、FC2-PPV-1234567）
    raw_number    VARCHAR(128),                         -- 文件名中识别出的原始番号
    title         TEXT,
    title_zh      TEXT,                                 -- 中文标题（若数据源提供）
    release_date  DATE,
    director      VARCHAR(128),
    maker         VARCHAR(128),                         -- 片商/厂商
    label         VARCHAR(128),                         -- 系列标签
    series        VARCHAR(128),
    duration_min  INTEGER,
    cover_url     TEXT,                                 -- 封面图 URL
    preview_urls  TEXT,                                 -- 剧照/预览图 URL，JSON 数组
    plot          TEXT,                                 -- 简介
    source        VARCHAR(16),                          -- 刮削来源：javbus | javdb | merged
    status        VARCHAR(16) NOT NULL DEFAULT 'pending', -- pending | scraped | failed
    fail_reason   TEXT,
    scraped_at    TIMESTAMP,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_movies_status ON movies (status);

-- 女优
CREATE TABLE actresses (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) UNIQUE NOT NULL
);

-- 标签/类型
CREATE TABLE genres (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) UNIQUE NOT NULL
);

-- 影片-女优 关联
CREATE TABLE movie_actresses (
    movie_id    BIGINT NOT NULL REFERENCES movies (id) ON DELETE CASCADE,
    actress_id  BIGINT NOT NULL REFERENCES actresses (id) ON DELETE CASCADE,
    PRIMARY KEY (movie_id, actress_id)
);

-- 影片-标签 关联
CREATE TABLE movie_genres (
    movie_id  BIGINT NOT NULL REFERENCES movies (id) ON DELETE CASCADE,
    genre_id  BIGINT NOT NULL REFERENCES genres (id) ON DELETE CASCADE,
    PRIMARY KEY (movie_id, genre_id)
);

-- 文件记录（源文件指纹绑定，参考 JavBoss：元数据绑定文件而非文件名）
CREATE TABLE file_records (
    id            BIGSERIAL PRIMARY KEY,
    source_path   TEXT UNIQUE NOT NULL,                 -- 源文件绝对路径
    file_name     TEXT NOT NULL,
    file_size     BIGINT NOT NULL,
    file_mtime    TIMESTAMP,                            -- 最后修改时间（指纹组成）
    number        VARCHAR(64),                          -- 识别出的番号（可空=未识别）
    recognize_status VARCHAR(16) NOT NULL DEFAULT 'unrecognized', -- recognized | unrecognized | filtered
    target_path   TEXT,                                 -- 整理后的目标路径
    link_mode     VARCHAR(16),                          -- hardlink | symlink | move | copy
    organize_status VARCHAR(16) NOT NULL DEFAULT 'pending', -- pending | organized | failed | skipped
    organize_error TEXT,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_file_records_number ON file_records (number);
CREATE INDEX idx_file_records_organize_status ON file_records (organize_status);

-- 异步任务
CREATE TABLE jobs (
    id           BIGSERIAL PRIMARY KEY,
    type         VARCHAR(16) NOT NULL,                  -- scan | scrape | organize | pipeline
    status       VARCHAR(16) NOT NULL DEFAULT 'running', -- running | completed | failed | cancelled
    params       TEXT,                                  -- 请求参数 JSON
    total_items  INTEGER NOT NULL DEFAULT 0,
    done_items   INTEGER NOT NULL DEFAULT 0,
    fail_items   INTEGER NOT NULL DEFAULT 0,
    error_detail TEXT,                                  -- 失败明细 JSON（路径/番号/原因）
    started_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at  TIMESTAMP
);

-- 运行时配置（数据库侧持久化，覆盖 application.yaml 默认值）
CREATE TABLE settings (
    key        VARCHAR(64) PRIMARY KEY,
    value      TEXT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
