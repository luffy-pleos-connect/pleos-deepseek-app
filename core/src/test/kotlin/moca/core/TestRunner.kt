package moca.core

import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

private class Failure(val suite: String, val test: String, val error: Throwable)

fun main(args: Array<String>) {
    val root = File(if (args.isNotEmpty()) args[0] else "build/classes/test")
    if (!root.exists()) {
        System.err.println("테스트 클래스 디렉터리가 없습니다: ${root.absolutePath}")
        exitProcess(2)
    }

    val suites = root.walkTopDown()
        .filter { it.isFile && it.name.endsWith("Test.class") && !it.name.contains('$') }
        .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
        .sorted()
        .toList()

    val failures = mutableListOf<Failure>()
    val perSuite = LinkedHashMap<String, Pair<Int, Int>>()
    var total = 0
    var passed = 0

    for (suiteName in suites) {
        val suite = Class.forName(suiteName)
        val tests = suite.declaredMethods
            .filter { it.isAnnotationPresent(Test::class.java) }
            .sortedBy { it.name }
        if (tests.isEmpty()) continue
        val instance = suite.getDeclaredConstructor().newInstance()
        var suitePass = 0
        for (method in tests) {
            total++
            try {
                method.isAccessible = true
                method.invoke(instance)
                suitePass++
                passed++
            } catch (e: InvocationTargetException) {
                failures += Failure(suite.simpleName, method.name, e.targetException ?: e)
            } catch (e: Throwable) {
                failures += Failure(suite.simpleName, method.name, e)
            }
        }
        perSuite[suite.simpleName] = suitePass to tests.size
    }

    println()
    println("── suites ─────────────────────────────────────────────")
    for ((name, counts) in perSuite) {
        val (ok, all) = counts
        val mark = if (ok == all) "OK  " else "FAIL"
        println(String.format("%s %-28s %3d/%3d", mark, name, ok, all))
    }
    if (failures.isNotEmpty()) {
        println()
        println("── failures ───────────────────────────────────────────")
        for (f in failures) {
            println("✗ ${f.suite}.${f.test}: ${f.error.message}")
            val trace = f.error.stackTrace.firstOrNull { it.className.startsWith("moca.core") }
            if (trace != null) println("    at ${trace.className}.${trace.methodName}(${trace.fileName}:${trace.lineNumber})")
        }
    }
    println()
    println("TOTAL=$total PASSED=$passed FAILED=${total - passed}")
    exitProcess(if (failures.isEmpty()) 0 else 1)
}
