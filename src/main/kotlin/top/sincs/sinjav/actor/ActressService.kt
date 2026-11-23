package top.sincs.sinjav.actor

import org.springframework.stereotype.Service

/**
 * 演员库领域服务：名字归一化 + 「演员 + 别名」装载。
 *
 * 演员名归一化是头像命中率的关键（设计文档）：刮削得到的女优名写法多样（简体中文名 / 日文名 /
 * 异体写法），`actress_aliases` 表把同一演员的各种写法映射到一条主记录，
 * [normalize] 把任意写法收敛到规范名（`actress.alias` 优先，回退 `actress.name`）。
 */
@Service
class ActressService(
    private val actressRepository: ActressRepository,
    private val actressAliasRepository: ActressAliasRepository,
) {

    /**
     * 归一化演员名：查 actress_aliases 表，命中则返回对应演员的规范名（`actress.alias` 或 `actress.name`），
     * 未命中返回原名（去首尾空白后的名字，空串原样返回，避免把 null 语义混进调用方）。
     *
     * 规范名 = Gfriends 匹配名 + 本地照片文件名（见 [ActorPhotoService]），
     * 因此别名表变更后重抓头像即可让文件名跟随新规范名。
     */
    fun normalize(name: String): String {
        val key = name.trim()
        if (key.isEmpty()) return name
        return actressRepository.findCanonicalNameByAlias(key)?.takeIf { it.isNotBlank() } ?: key
    }

    /** 演员的规范名：alias（非空）优先，否则 name。头像匹配与文件命名统一用它 */
    fun canonicalName(actress: ActressEntity): String =
        actress.alias?.takeIf { it.isNotBlank() } ?: actress.name

    /**
     * 获取演员及其所有别名（供 [GfriendsService.findPhoto] 按名字优先级逐一尝试）。
     *
     * @throws NoSuchElementException 演员不存在（由 GlobalExceptionHandler 映射为 404）
     */
    fun getActressWithAliases(actressId: Long): Pair<ActressEntity, List<String>> {
        val actress = actressRepository.findById(actressId) ?: throw NoSuchElementException("演员不存在: $actressId")
        val aliases = actressAliasRepository.findByActressId(actressId).map { it.alias }
        return actress to aliases
    }

    /** 按 id 查演员（不存在抛 [NoSuchElementException]） */
    fun getActress(actressId: Long): ActressEntity =
        actressRepository.findById(actressId) ?: throw NoSuchElementException("演员不存在: $actressId")

    /** 别名列表（不含规范名本身；Gfriends 匹配按录入顺序） */
    fun aliasesOf(actressId: Long): List<String> = actressAliasRepository.findAliases(actressId)

    /** 尚无本地照片的演员 id（批量补全的默认候选池） */
    fun idsMissingPhoto(): List<Long> = actressRepository.findIdsMissingPhoto()

    /**
     * 批量并入演员库（名字先经 [normalize] 归一化，空白名跳过）。
     *
     * 供「顺手扩充演员库」的调用方使用（如 Emby 重刮收尾：重刮落库的演员名并入本地，
     * 新演员再交给头像自动补全）。
     *
     * @return 本次**新建**的演员 id（已存在的不计入），保持入参顺序
     */
    fun registerAll(names: List<String>): List<Long> {
        val newIds = LinkedHashSet<Long>()
        names.forEach { raw ->
            val name = normalize(raw)
            if (name.isNotBlank()) actressRepository.insertIfAbsent(name)?.let { newIds += it }
        }
        return newIds.toList()
    }

    // ---------------------------------------------------------------- 演员管理（批次 5）

    /** 分页列表（含关联影片数）：[search] 匹配 name/alias/别名表，[hasPhoto] 三态过滤（null = 不过滤） */
    fun listPaged(page: Int, size: Int, search: String?, hasPhoto: Boolean?): ActressRepository.Page =
        actressRepository.findAllPaged(page, size, search, hasPhoto)

    /**
     * 演员详情（演员 + 别名 + 所属影片）。
     *
     * @throws NoSuchElementException 演员不存在（由 GlobalExceptionHandler 映射为 404）
     */
    fun getDetail(actressId: Long): Pair<ActressRepository.ActressDetail, List<String>> {
        val detail = actressRepository.findByIdWithMovies(actressId)
            ?: throw NoSuchElementException("演员不存在: $actressId")
        return detail to aliasesOf(actressId)
    }

    /**
     * 编辑演员：更新规范名 + 全量替换别名（详情页「别名 tag 编辑」语义）。
     *
     * - [alias]：null = 不修改；空串/空白 = 清空（[canonicalName] 回退 `name`）；
     * - [aliases]：全量替换，空集合即清空；条目 trim + 去重后落库；
     * - 改规范名**不改**已有照片文件名（`photo_path` 仍指向旧文件，依旧可访问），需要新文件名时
     *   再调 `POST /actors/{id}/scrape-photo` 重抓一次即可（`scrapePhoto` 会清理旧文件）。
     *
     * @throws NoSuchElementException 演员不存在
     * @throws IllegalArgumentException 别名已被其他演员占用（`actress_aliases.alias` 全局唯一，先检查再写，
     *   避免 `replaceForActress` 静默跳过导致「编辑成功但别名丢失」）
     */
    fun updateAliases(actressId: Long, alias: String?, aliases: List<String>): ActressEntity {
        val actress = getActress(actressId)
        val cleaned = aliases.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val conflicts = cleaned.filter { name ->
            actressAliasRepository.findByAlias(name)?.let { it.actressId != actressId } == true
        }
        if (conflicts.isNotEmpty()) {
            throw IllegalArgumentException("别名已被其他演员占用: ${conflicts.joinToString("、")}")
        }

        // alias 列可为空：显式传空串表示「清除规范名」，null 表示本次不改
        if (alias != null) {
            val newAlias = alias.trim().ifEmpty { null }
            if (newAlias != actress.alias) actressRepository.updateAlias(actressId, newAlias)
        }
        actressAliasRepository.replaceForActress(actressId, cleaned)
        return getActress(actressId)
    }
}
