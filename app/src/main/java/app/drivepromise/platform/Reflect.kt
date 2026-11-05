package app.drivepromise.platform

import app.drivepromise.L
import java.lang.reflect.Proxy

/**
 * Pleos SDK 리스너를 실행 중에 만듦(공식 샘플 VehicleApiExplorer·G1-B 프로브와 같은 방식).
 * SDK 콜백 이름·인자 모양이 버전마다 조금씩 달라도 컴파일이 깨지지 않게, 콜백은 이름 + 인자 toString으로 받습니다.
 */
object Reflect {
    @Suppress("UNCHECKED_CAST")
    fun <T> proxyOf(cls: Class<T>, name: String, h: (String, Array<out Any?>?) -> Unit): T =
        Proxy.newProxyInstance(cls.classLoader, arrayOf<Class<*>>(cls)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> name
                else -> {
                    try {
                        h(method.name, args)
                    } catch (e: Throwable) {
                        L.log("proxy_error", "name" to name, "m" to method.name, "err" to e)
                    }
                    null
                }
            }
        } as T

    /** "key=value" 꼴(data class toString)에서 숫자 하나 */
    fun num(s: String, key: String): Double? = Regex("\\b$key=(-?[0-9]+(?:\\.[0-9]+)?)").find(s)?.groupValues?.get(1)?.toDoubleOrNull()

    /** "key=WORD" 꼴에서 단어 하나(enum 이름 등) */
    fun word(s: String, key: String): String? = Regex("\\b$key=([A-Za-z_][A-Za-z0-9_]*)").find(s)?.groupValues?.get(1)

    /** "key=값" 꼴에서 쉼표·괄호 전까지의 글(도로 이름 등) */
    fun text(s: String, key: String): String? = Regex("\\b$key=([^,)\\]]+)").find(s)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
