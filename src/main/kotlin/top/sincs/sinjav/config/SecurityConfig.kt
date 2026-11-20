package top.sincs.sinjav.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository

/**
 * 登录安全配置（单用户）：
 * - 放行 /api/v1/auth 下的全部端点（status/init/login/logout/credentials）与本地图片静态资源；
 * - 其余 /api/v1 路径需要登录会话，未登录返回 401（ProblemDetail 兼容格式，前端 request() 统一解析）；
 * - CSRF 关闭（同源 SPA + Session Cookie SameSite=Lax）；SPA 静态资源全部放行。
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .authorizeHttpRequests {
                it.requestMatchers("/api/v1/auth/**").permitAll()
                    .requestMatchers("/local-images/**").permitAll()
                    .requestMatchers("/api/v1/**").authenticated()
                    .anyRequest().permitAll()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    response.status = 401
                    response.contentType = "application/problem+json;charset=UTF-8"
                    response.writer.write(
                        """{"title":"Unauthorized","detail":"未登录或会话已过期","status":401}""",
                    )
                }
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .securityContext {
                it.securityContextRepository(HttpSessionSecurityContextRepository())
            }
        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    /** 登录控制器手动建立会话时使用的存储（与过滤器链一致） */
    @Bean
    fun securityContextRepository(): SecurityContextRepository = HttpSessionSecurityContextRepository()
}
