package app.drivepromise.core

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** ./gradlew :core:test — Specs.all을 JUnit 5 동적 시험으로 돌립니다(실패 메시지 앞에 스펙 이름) */
class CoreSpecsTest {
    @TestFactory
    fun specs(): List<DynamicTest> = Specs.all.map { (name, body) ->
        DynamicTest.dynamicTest(name) {
            try {
                body()
            } catch (t: Throwable) {
                throw AssertionError("$name — ${t.message}", t)
            }
        }
    }
}
