package top.sincs.sinjav.common

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException
import top.sincs.sinjav.job.JobConflictException

/**
 * 全局异常处理（所有 Controller 端点共用，统一返回 RFC 9457 ProblemDetail）：
 *
 * - 校验失败 / 参数类型不匹配 / 请求体解析失败 / 缺少必填参数 → 400
 * - 资源不存在（`NoSuchElementException`）→ 404
 * - 任务冲突（同类型任务已在运行）→ 409
 * - 认证失败（[AuthException]）→ 其自带状态码（401/409/429）
 * - 其他未捕获异常 → 500，并记录 ERROR 日志（含堆栈），便于排查
 *
 * 说明：演员管理端点（`/api/v1/actors`）的 404/400/409 都走这里，Controller 内不再各自 try-catch，
 * 保证前端 `ApiError` 只处理一种错误结构。
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求参数校验失败").apply {
            title = "Validation failed"
            setProperty(
                "errors",
                e.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to (it.defaultMessage ?: "invalid")) },
            )
        }

    /** 查询参数类型不合法（如 `?page=abc`、`?hasPhoto=maybe`）：不处理会落到兜底 500 */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "参数 ${e.name} 取值非法: ${e.value ?: "null"}",
        ).apply { title = "Bad request" }

    /** 缺少必填查询参数（如 `/videos` 未传 path） */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParameter(e: MissingServletRequestParameterException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "缺少必填参数: ${e.parameterName}").apply {
            title = "Bad request"
        }

    /** 请求体无法解析（JSON 语法错误 / 字段类型不符） */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(e: HttpMessageNotReadableException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求体格式错误，需为合法 JSON").apply {
            title = "Bad request"
        }

    /** HTTP 方法不支持（如对 `/actors` 发 PUT） */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(e: HttpRequestMethodNotSupportedException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, "不支持的请求方法: ${e.method}").apply {
            title = "Method not allowed"
        }

    /**
     * 路径不存在（无匹配的 Controller/静态资源）。
     * 不单独处理会落到兜底 500——把「地址打错」误报成服务端故障。
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(e: NoResourceFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "接口或资源不存在: /${e.resourcePath}").apply {
            title = "Not found"
        }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message ?: "非法参数").apply { title = "Bad request" }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(e: NoSuchElementException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message ?: "资源不存在").apply { title = "Not found" }

    @ExceptionHandler(AuthException::class)
    fun handleAuth(e: AuthException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status), e.message ?: "认证失败").apply {
            title = if (e.status == 401) "Unauthorized" else "Auth error"
        }

    @ExceptionHandler(JobConflictException::class)
    fun handleConflict(e: JobConflictException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message ?: "任务冲突").apply { title = "Job conflict" }

    /** 兜底：未预期的异常记为 ERROR（带堆栈），对外只暴露消息，避免把内部细节与堆栈泄露给前端 */
    @ExceptionHandler(Exception::class)
    fun handleGeneric(e: Exception): ProblemDetail {
        log.error("未处理的接口异常: {}", e.message, e)
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.message ?: "内部错误").apply {
            title = "Internal error"
        }
    }
}
