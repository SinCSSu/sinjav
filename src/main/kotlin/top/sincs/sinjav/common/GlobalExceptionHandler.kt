package top.sincs.sinjav.common

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求参数校验失败").apply {
            title = "Validation failed"
            setProperty(
                "errors",
                e.bindingResult.fieldErrors.map { mapOf("field" to it.field, "message" to (it.defaultMessage ?: "invalid")) },
            )
        }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.message ?: "非法参数").apply { title = "Bad request" }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(e: NoSuchElementException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.message ?: "资源不存在").apply { title = "Not found" }

    @ExceptionHandler(top.sincs.sinjav.job.JobConflictException::class)
    fun handleConflict(e: top.sincs.sinjav.job.JobConflictException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.message ?: "任务冲突").apply { title = "Job conflict" }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(e: Exception): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.message ?: "内部错误").apply {
            title = "Internal error"
        }
}
