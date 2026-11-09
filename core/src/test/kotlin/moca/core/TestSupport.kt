package moca.core

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Test

object Assert {
    fun eq(expected: Any?, actual: Any?, label: String = "") {
        if (expected != actual) {
            throw java.lang.AssertionError("${prefix(label)}expected=<$expected> actual=<$actual>")
        }
    }

    fun ne(unexpected: Any?, actual: Any?, label: String = "") {
        if (unexpected == actual) {
            throw java.lang.AssertionError("${prefix(label)}did not expect <$unexpected>")
        }
    }

    fun near(expected: Double?, actual: Double?, eps: Double = 1e-9, label: String = "") {
        if (expected == null || actual == null) {
            if (expected != actual) {
                throw java.lang.AssertionError("${prefix(label)}expected=<$expected> actual=<$actual>")
            }
            return
        }
        if (kotlin.math.abs(expected - actual) > eps) {
            throw java.lang.AssertionError("${prefix(label)}expected≈<$expected> actual=<$actual>")
        }
    }

    fun nearNullable(expected: Double?, actual: Double?, eps: Double = 1e-9, label: String = "") {
        if (expected == null || actual == null) {
            if (expected != actual) {
                throw java.lang.AssertionError("${prefix(label)}expected=<$expected> actual=<$actual>")
            }
            return
        }
        near(expected, actual, eps, label)
    }

    fun isTrue(value: Boolean, label: String = "") {
        if (!value) throw java.lang.AssertionError("${prefix(label)}expected true")
    }

    fun isFalse(value: Boolean, label: String = "") {
        if (value) throw java.lang.AssertionError("${prefix(label)}expected false")
    }

    fun isNull(value: Any?, label: String = "") {
        if (value != null) throw java.lang.AssertionError("${prefix(label)}expected null but was <$value>")
    }

    fun notNull(value: Any?, label: String = "") {
        if (value == null) throw java.lang.AssertionError("${prefix(label)}expected non-null")
    }

    fun fail(label: String): Nothing = throw java.lang.AssertionError(prefix(label) + "failed")

    fun throws(label: String = "", block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            return
        }
        throw java.lang.AssertionError(prefix(label) + "expected an exception")
    }

    private fun prefix(label: String): String = if (label.isEmpty()) "" else "$label: "
}
