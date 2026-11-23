-- 通用应用元数据（键值对）：存运行期状态，如 Gfriends 索引最后同步时间。
-- 与 settings 表的分工：settings 存用户可改的配置覆盖值，app_metadata 存程序自己维护的标记。

CREATE TABLE app_metadata (
    key        VARCHAR(128) PRIMARY KEY,
    value      TEXT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
