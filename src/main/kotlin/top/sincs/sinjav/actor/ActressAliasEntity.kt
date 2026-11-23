package top.sincs.sinjav.actor

/**
 * 演员别名（actress_aliases 表）：同一演员的多种写法（中文名 / 异体写法），
 * Gfriends 头像匹配时逐一尝试。[id] 为 null 表示尚未落库。
 */
data class ActressAliasEntity(
    val id: Long? = null,
    val actressId: Long,
    val alias: String,
)
