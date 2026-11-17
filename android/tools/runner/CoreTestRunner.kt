// Minimal reflection-based runner for the pure-Kotlin core tests, used ONLY by tools/core-check
// (the sandbox has kotlin-test.jar but no JUnit). Under Gradle the same test classes run with
// kotlin-test-junit. Exits non-zero on any failure.
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.net.URLClassLoader

fun main(args: Array<String>) {
    val classesDir = File(args.firstOrNull() ?: error("usage: CoreTestRunner <classesDir>"))
    val loader = URLClassLoader(arrayOf(classesDir.toURI().toURL()), Thread.currentThread().contextClassLoader)
    val testClasses = classesDir.walkTopDown()
        .filter { it.isFile && it.name.endsWith("Test.class") && !it.name.contains('$') }
        .map { it.relativeTo(classesDir).path.removeSuffix(".class").replace(File.separatorChar, '.') }
        .sorted()
        .toList()
    var run = 0
    var failed = 0
    val failures = mutableListOf<String>()
    for (className in testClasses) {
        val cls = loader.loadClass(className)
        val methods = cls.methods.filter { m ->
            m.isAnnotationPresent(org.junit.Test::class.java) && m.parameterCount == 0 && !Modifier.isStatic(m.modifiers)
        }.sortedBy { it.name }
        if (methods.isEmpty()) continue
        println("== $className (${methods.size} tests)")
        for (m in methods) {
            run++
            val instance = cls.getDeclaredConstructor().newInstance()
            try {
                m.invoke(instance)
                println("   ok   ${m.name}")
            } catch (e: InvocationTargetException) {
                failed++
                val cause = e.targetException
                val msg = "   FAIL ${m.name}: ${cause::class.java.simpleName}: ${cause.message}"
                println(msg)
                failures += "$className.${m.name}: ${cause.message}"
                cause.stackTrace.take(4).forEach { println("        at $it") }
            }
        }
    }
    println()
    println("core-check: $run tests, $failed failed, ${testClasses.size} classes")
    if (failed > 0 || run == 0) {
        failures.forEach { println(" - $it") }
        System.exit(1)
    }
}
