-- 剧照逐张来源（与 cover_candidates 同构）：preview_candidates 为 JSON 数组
-- [{"url":"...","source":"javbus"}]，记录每张剧照由哪个数据源产生。
-- 多源合并后 preview_urls 里混有各源图床的图（javbus→pics.dmm.co.jp、javdb→c0.jdbstatic.com），
-- 只有单一 preview_source 会把图发到错误的出口通道（过盾/直连），故逐张持久化来源供下载路由。
ALTER TABLE movies ADD COLUMN preview_candidates TEXT;
