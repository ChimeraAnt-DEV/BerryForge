package dev.chimeraant.berryforge.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the build log parser.
 *
 * Two of these cover bugs that shipped: javac/clang warnings were classified as
 * errors, and Gradle's "Class > method FAILED" lines were never recognised, so the
 * failing-test list was always empty.
 */
class BuildLogParserTest {

    private val parser = BuildLogParser()

    @Test
    fun kotlinErrorIsAnErrorWithFileAndLine() {
        val line = parser.classify("e: file:///w/app/src/main/java/Foo.kt:12:5 Unresolved reference: bar")
        assertEquals(LogSeverity.Error, line.severity)
        val error = assertNotNull(line.error)
        assertEquals("Foo.kt", line.error!!.path.substringAfterLast('/'))
        assertEquals(12, line.error!!.line)
        assertEquals(5, line.error!!.column)
        assertEquals(ErrorKind.Kotlin, line.error!!.kind)
    }

    @Test
    fun kotlinWarningIsAWarning() {
        val line = parser.classify("w: file:///w/app/src/main/java/Foo.kt:20:9 Variable 'x' is never used")
        assertEquals(LogSeverity.Warn, line.severity)
    }

    @Test
    fun javacErrorIsAnError() {
        val line = parser.classify("/w/app/src/main/java/Foo.java:14: error: cannot find symbol")
        assertEquals(LogSeverity.Error, line.severity)
        assertEquals(14, line.error!!.line)
        assertEquals(ErrorKind.Java, line.error!!.kind)
    }

    /** Regression: the old pattern did not capture the severity word. */
    @Test
    fun javacWarningIsAWarningNotAnError() {
        val line = parser.classify("/w/app/src/main/java/Foo.java:14: warning: [deprecation] foo() is deprecated")
        assertEquals(LogSeverity.Warn, line.severity)
    }

    @Test
    fun clangWarningIsAWarning() {
        val line = parser.classify("/w/app/src/main/cpp/native.cpp:8:3: warning: unused variable 'y'")
        assertEquals(LogSeverity.Warn, line.severity)
    }

    @Test
    fun clangErrorIsAnErrorWithColumn() {
        val line = parser.classify("/w/app/src/main/cpp/native.cpp:8:3: error: expected ';'")
        assertEquals(LogSeverity.Error, line.severity)
        assertEquals(3, line.error!!.column)
        assertEquals(ErrorKind.Cpp, line.error!!.kind)
    }

    @Test
    fun failedTaskIsAnError() {
        assertEquals(LogSeverity.Error, parser.classify("> Task :app:compileDebugKotlin FAILED").severity)
    }

    @Test
    fun buildOutcomeLinesAreClassified() {
        assertEquals(LogSeverity.Error, parser.classify("BUILD FAILED in 12s").severity)
        assertEquals(LogSeverity.Success, parser.classify("BUILD SUCCESSFUL in 4s").severity)
    }

    /** Regression: the old pattern required the line to start with FAILED. */
    @Test
    fun gradleTestFailureLineIsRecognised() {
        val line = parser.classify("com.example.MyTest > testAddition FAILED")
        assertEquals(LogSeverity.Error, line.severity)
        assertEquals(ErrorKind.Test, line.error!!.kind)
        assertTrue(line.error!!.message.contains("testAddition"))
    }

    @Test
    fun indentedGradleTestFailureIsRecognised() {
        assertEquals(LogSeverity.Error, parser.classify("    MyTest > testAddition FAILED").severity)
    }

    @Test
    fun plainFailedWithColonIsATestFailure() {
        val line = parser.classify("FAILED: com.example.MyTest.testAddition")
        assertEquals(LogSeverity.Error, line.severity)
        assertEquals(ErrorKind.Test, line.error!!.kind)
    }

    @Test
    fun proseMentioningErrorsIsNotAnError() {
        assertEquals(LogSeverity.Info, parser.classify("There were failing tests. See the report at: file:///x").severity)
        assertEquals(LogSeverity.Info, parser.classify("3 tests completed, 1 failed").severity)
    }

    @Test
    fun ordinaryTaskLinesAreInfo() {
        assertEquals(LogSeverity.Info, parser.classify("> Task :app:testDebugUnitTest").severity)
        assertEquals(LogSeverity.Info, parser.classify("Downloading https://example.com/x.zip").severity)
    }

    @Test
    fun errorsFromDeduplicatesIdenticalEntries() {
        val lines = listOf(
            "e: file:///w/A.kt:1:1 one",
            "w: file:///w/A.kt:2:1 a warning",
            "e: file:///w/A.kt:1:1 one",
            "BUILD FAILED",
        ).map { parser.classify(it) }
        val errors = parser.errorsFrom(lines)
        // Two distinct locations (line 1 repeated, and the warning on line 2); the
        // duplicate of line 1 collapses, and "BUILD FAILED" carries no error.
        assertEquals(2, errors.size)
        assertEquals(listOf(1, 2), errors.map { it.line })
    }

    @Test
    fun manifestErrorsAreLinkedToTheManifest() {
        val line = parser.classify("app/src/main/AndroidManifest.xml:24:5: error: unexpected element")
        assertEquals(LogSeverity.Error, line.severity)
        assertEquals(ErrorKind.Manifest, line.error!!.kind)
        assertEquals(24, line.error!!.line)
    }

    @Test
    fun gradleScriptErrorsCarryALine() {
        val line = parser.classify("build.gradle.kts:18: unresolved reference: foo")
        assertEquals(LogSeverity.Error, line.severity)
        assertEquals(ErrorKind.Gradle, line.error!!.kind)
    }

    @Test
    fun blankLinesAreInfoAndCarryNoError() {
        val line = parser.classify("")
        assertEquals(LogSeverity.Info, line.severity)
        assertNull(line.error)
    }
}
