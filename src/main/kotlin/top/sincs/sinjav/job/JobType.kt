package top.sincs.sinjav.job

/**
 * 任务类型（jobs.type 列的取值域）。
 *
 * 项目里 jobs.type 一直是字符串（SQL 中无枚举约束），本枚举只是把这些字面量集中一处，
 * 供新代码（演员头像等）引用，避免各处散落 "actor_photo_auto" 之类的魔法字符串。
 * 存量任务类型（scan/scrape/organize/pipeline）保持不变，[code] 与其历史取值完全一致。
 */
enum class JobType(val code: String) {
    SCAN("scan"),
    SCRAPE("scrape"),
    ORGANIZE("organize"),
    PIPELINE("pipeline"),

    /** 刮削/Emby 重刮收尾时自动补全新演员头像（双入口共用，见 ActorPhotoService.scrapePhotosAsync） */
    ACTOR_PHOTO_AUTO("actor_photo_auto"),

    /** 演员列表页「补全头像」批量任务 */
    ACTOR_PHOTO_BATCH("actor_photo_batch"),

    /** Emby 演员同步：遍历 emby_items 快照拉条目演员，归一化合并入 actresses（批次 4） */
    EMBY_ACTOR_SYNC("emby_actor_sync"),
    ;

    companion object {
        /** 按 code 反查（未知 code 返回 null；前端筛选/恢复逻辑用） */
        fun fromCode(code: String): JobType? = entries.firstOrNull { it.code == code }
    }
}
