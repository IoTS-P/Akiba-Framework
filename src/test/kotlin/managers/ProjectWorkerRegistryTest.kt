package managers

import org.iotsplab.akiba.managers.ProjectWorkerRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Session lookups the pooled topology needs, without any process handling mixed in. */
class ProjectWorkerRegistryTest {

    @Test
    fun `many sessions can sit on one project`() {
        val registry = ProjectWorkerRegistry()
        registry.attach("session-a", "fx20")
        registry.attach("session-b", "fx20")

        assertEquals(setOf("session-a", "session-b"), registry.sessionsOn("fx20"))
        assertEquals("fx20", registry.projectOf("session-b"))
        assertFalse(registry.isIdle("fx20"))
    }

    @Test
    fun `detaching one session leaves the others`() {
        val registry = ProjectWorkerRegistry()
        registry.attach("session-a", "fx20")
        registry.attach("session-b", "fx20")

        registry.detach("session-a")
        assertEquals(setOf("session-b"), registry.sessionsOn("fx20"))
        assertNull(registry.projectOf("session-a"))

        registry.detach("session-b")
        assertTrue(registry.isIdle("fx20"), "the pool may now let the worker go")
    }

    @Test
    fun `moving a session between projects does not leave a ghost`() {
        val registry = ProjectWorkerRegistry()
        registry.attach("session-a", "fx20")
        registry.attach("session-a", "other")

        assertEquals("other", registry.projectOf("session-a"))
        assertTrue(registry.isIdle("fx20"), "the old project must not keep a phantom session")
        assertEquals(setOf("session-a"), registry.sessionsOn("other"))
    }

    @Test
    fun `switching the topology is an environment decision`() {
        // Not set in the test environment: the per-turn worker stays the default.
        assertFalse(ProjectWorkerRegistry.poolingEnabled())
    }
}
