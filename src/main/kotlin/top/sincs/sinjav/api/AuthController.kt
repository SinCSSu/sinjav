package top.sincs.sinjav.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.sincs.sinjav.common.AuthException
import top.sincs.sinjav.config.AuthService

@Validated
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val auth: AuthService,
    private val securityContextRepository: SecurityContextRepository,
) {

    data class InitRequest(
        @field:NotBlank(message = "用户名必填")
        @field:Size(max = 64, message = "用户名过长")
        val username: String,
        @field:NotBlank(message = "密码必填")
        @field:Size(min = 6, max = 128, message = "密码长度需为 6-128 位")
        val password: String,
    )

    data class LoginRequest(
        @field:NotBlank(message = "用户名必填") val username: String,
        @field:NotBlank(message = "密码必填") val password: String,
    )

    data class CredentialsRequest(
        @field:NotBlank(message = "当前密码必填") val currentPassword: String,
        @field:Size(max = 64) val username: String?,
        @field:Size(min = 6, max = 128, message = "新密码长度需为 6-128 位") val newPassword: String?,
    )

    data class AuthStatus(val initialized: Boolean, val username: String?, val authenticated: Boolean)
    data class LoginResult(val username: String)

    /** 登录页/前端启动时调用：是否已初始化 + 当前登录态与用户名 */
    @GetMapping("/status")
    fun status(): AuthStatus {
        val cred = auth.load()
        // 本应用的登录会话由 establishSession 手动建立（主体为用户名字符串），据此判断登录态
        val loggedIn = SecurityContextHolder.getContext().authentication is UsernamePasswordAuthenticationToken
        return AuthStatus(
            initialized = cred != null,
            username = cred?.username,
            authenticated = loggedIn,
        )
    }

    /** 首次初始化（仅未初始化时可用），成功后自动登录 */
    @PostMapping("/init")
    fun init(@Valid @RequestBody req: InitRequest, request: HttpServletRequest, response: HttpServletResponse): LoginResult {
        auth.init(req.username, req.password)
        establishSession(req.username.trim(), request, response)
        return LoginResult(req.username.trim())
    }

    /** 登录：防爆破锁定 → 验证凭据 → 失败计数/成功重建会话（续期） */
    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest, request: HttpServletRequest, response: HttpServletResponse): LoginResult {
        auth.checkLock()
        if (!auth.verify(req.username.trim(), req.password)) {
            auth.recordFailure()
            throw AuthException(401, "用户名或密码错误")
        }
        auth.resetFailures()
        establishSession(req.username.trim(), request, response)
        return LoginResult(req.username.trim())
    }

    /** 退出登录：清上下文并销毁会话 */
    @PostMapping("/logout")
    fun logout(request: HttpServletRequest): ResponseEntity<Unit> {
        SecurityContextHolder.clearContext()
        request.getSession(false)?.invalidate()
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
    }

    /** 修改用户名/密码（需验证当前密码），改密成功后保持当前会话 */
    @PutMapping("/credentials")
    fun updateCredentials(@Valid @RequestBody req: CredentialsRequest): LoginResult {
        if (req.username.isNullOrBlank() && req.newPassword.isNullOrBlank()) {
            throw IllegalArgumentException("用户名与新密码至少填一项")
        }
        auth.updateCredentials(req.currentPassword, req.username?.trim()?.ifBlank { null }, req.newPassword)
        return LoginResult(auth.load()?.username ?: req.username?.trim() ?: "")
    }

    /** 建立会话：先换新会话（防会话固定），再写入安全上下文，Cookie 30 天 TTL 由全局配置承载 */
    private fun establishSession(username: String, request: HttpServletRequest, response: HttpServletResponse) {
        val token = UsernamePasswordAuthenticationToken(
            username, null, listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = token
        SecurityContextHolder.setContext(context)
        request.getSession(false)?.invalidate()
        request.getSession(true)
        securityContextRepository.saveContext(context, request, response)
    }
}
