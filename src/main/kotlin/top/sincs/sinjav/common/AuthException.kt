package top.sincs.sinjav.common

/** 认证相关异常：status 映射为 HTTP 状态码（401 凭据错误 / 429 触发防爆破锁定 / 409 已初始化等） */
class AuthException(val status: Int, message: String) : RuntimeException(message)
