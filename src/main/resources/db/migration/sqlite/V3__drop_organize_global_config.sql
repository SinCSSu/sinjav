-- 目标目录与整理方式改为每次整理必填，不再走全局配置
DELETE FROM settings WHERE key IN ('targetDir', 'mode');
