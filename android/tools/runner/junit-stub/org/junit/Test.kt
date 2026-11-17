// Compile-time stub used ONLY by tools/core-check: kotlin-test-junit declares `kotlin.test.Test` as a
// typealias of org.junit.Test, and the sandbox has no JUnit jar. Gradle uses the real JUnit 4.13.2.
package org.junit

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test
