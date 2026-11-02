package app.daybit.global.security

import app.daybit.global.error.ApiException
import app.daybit.global.error.ErrorCode
import org.springframework.core.MethodParameter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * 컨트롤러 파라미터에 붙이면 로그인한 사용자의 id(Long)가 들어와요.
 * `fun me(@LoginUser userId: Long)`
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class LoginUser

@Component
class LoginUserArgumentResolver : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter) =
        parameter.hasParameterAnnotation(LoginUser::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Long {
        val principal = SecurityContextHolder.getContext().authentication?.principal
        val jwt = principal as? Jwt ?: throw ApiException(ErrorCode.UNAUTHORIZED)
        return jwt.subject?.toLongOrNull() ?: throw ApiException(ErrorCode.UNAUTHORIZED)
    }
}
