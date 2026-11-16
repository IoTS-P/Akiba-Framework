package module

import org.iotsplab.akiba.module.AkibaModule
import org.iotsplab.akiba.module.ExitCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How a module's ending is named, and which endings stop a chain. */
class ExitCodeTest {

    @Test
    fun `the framework's own endings have names`() {
        assertEquals("SUCCESS", ExitCode.nameOf(ExitCode.SUCCESS))
        assertEquals("FAILED", ExitCode.nameOf(ExitCode.FAILED))
        assertEquals("RUNTIME_ERROR", ExitCode.nameOf(ExitCode.RUNTIME_ERROR))
        assertEquals("TIMEOUT", ExitCode.nameOf(ExitCode.TIMEOUT))
        assertEquals("CANCELLED", ExitCode.nameOf(ExitCode.CANCELLED))
        assertEquals("SKIPPED", ExitCode.nameOf(ExitCode.SKIPPED))
    }

    @Test
    fun `a module's own code still reads as a number`() {
        assertEquals("EXIT_100", ExitCode.nameOf(ExitCode.FIRST_CUSTOM))
        assertEquals("EXIT_4096", ExitCode.nameOf(4096))
    }

    @Test
    fun `the old constants and the new ones agree`() {
        assertEquals(ExitCode.SUCCESS, AkibaModule.SUCCESS)
        assertEquals(ExitCode.FAILED, AkibaModule.FAILED)
        assertEquals(ExitCode.RUNTIME_ERROR, AkibaModule.RUNTIME_ERROR)
    }

    @Test
    fun `framework failures stop a chain, module codes do not`() {
        listOf(ExitCode.FAILED, ExitCode.RUNTIME_ERROR, ExitCode.TIMEOUT, ExitCode.CANCELLED)
            .forEach { assertTrue(ExitCode.isFatal(it), "code $it should be fatal") }

        assertFalse(ExitCode.isFatal(ExitCode.SUCCESS))
        assertFalse(ExitCode.isFatal(ExitCode.FIRST_CUSTOM))
        assertFalse(ExitCode.isFatal(1000))
    }

    @Test
    fun `anything but zero is a failure, however it is spelled`() {
        assertFalse(ExitCode.isFailure(ExitCode.SUCCESS))
        assertTrue(ExitCode.isFailure(ExitCode.SKIPPED))
        assertTrue(ExitCode.isFailure(ExitCode.FIRST_CUSTOM))
    }
}
