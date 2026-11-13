package plan

import org.iotsplab.akiba.plan.Outcome
import org.iotsplab.akiba.plan.Plan
import org.iotsplab.akiba.plan.PlanStepException
import org.iotsplab.akiba.plan.PlanWriter
import org.iotsplab.akiba.plan.ProjectExistsException
import org.iotsplab.akiba.plan.Step
import org.iotsplab.akiba.plan.StepKind
import org.iotsplab.akiba.plan.outcomeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A failed verb must be inspectable instead of fatal: that is the whole point of
 * returning [Outcome] rather than throwing at every call site.
 */
class PlanOutcomeTest {

    @Test
    fun `a failed outcome stays quiet until the script asks for the value`() {
        val failed: Outcome<String> = Outcome.Failed(ProjectExistsException("fx20_batch"))

        var seen: String? = null
        failed.onFailure { seen = it.message }
        assertTrue(seen!!.contains("fx20_batch"), "handler saw the reason")
        assertNull(failed.getOrNull())

        val thrown = assertFailsWith<ProjectExistsException> { failed.expect() }
        assertEquals("fx20_batch", thrown.project)
        assertIs<Exception>(thrown, "a plan failure is a normal exception once expected")
    }

    @Test
    fun `a script can recover from a taken name`() {
        val failed: Outcome<String> = Outcome.Failed(ProjectExistsException("fx20_batch"))
        val reused = failed.recover { "open existing" }

        assertEquals("open existing", reused.expect())
    }

    @Test
    fun `outcomeOf wraps only plan failures`() {
        assertEquals("ok", outcomeOf { "ok" }.expect())
        assertIs<Outcome.Failed>(outcomeOf<Int> { throw ProjectExistsException("a") })

        // Anything else is a bug in the script, not a plan condition: it propagates.
        assertFailsWith<IllegalStateException> {
            outcomeOf<Int> { throw IllegalStateException("boom") }
        }
    }

    @Test
    fun `createOrOpenProject is recorded as an open that may create`() {
        val writer = PlanWriter()
        val plan = Plan(
            script = "plan.kts",
            scriptHash = "sha256:bb",
            instance = "test",
            steps = listOf(
                Step(1, StepKind.OpenProject("fx20_batch", createIfMissing = true)),
            ),
        )

        val back = writer.decode(writer.encode(plan))
        val reopened = assertIs<StepKind.OpenProject>((back.steps.single()).kind)
        assertTrue(reopened.createIfMissing, "the flag survives the file")
    }

    @Test
    fun `a step failure names the step and the kind`() {
        val error = PlanStepException(stepNumber = 4, kind = "import", message = "disk full")
        assertEquals(4, error.step)
        assertTrue(error.message!!.contains("import") && error.message!!.contains("disk full"))
    }
}
