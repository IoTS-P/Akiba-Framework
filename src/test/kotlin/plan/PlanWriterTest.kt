package plan

import org.iotsplab.akiba.plan.Failure
import org.iotsplab.akiba.plan.ItemSet
import org.iotsplab.akiba.plan.Mode
import org.iotsplab.akiba.plan.Plan
import org.iotsplab.akiba.plan.PlanWriter
import org.iotsplab.akiba.plan.Step
import org.iotsplab.akiba.plan.StepKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The plan file has to stay small no matter how many programs a filter matched, and
 * it has to round-trip nested groups so a parallel block is not flattened by accident.
 */
class PlanWriterTest {

    private val writer = PlanWriter(spillThreshold = 500)

    @Test
    fun `a large item set spills to a side file and keeps the plan small`() {
        val dir = File.createTempFile("plan", "").let { it.delete(); it.mkdirs(); it }
        val ids = (1..3000).map { "00000000-0000-0000-0000-%012d".format(it) }

        val items = writer.items(step = 2, ids = ids, dir = dir)
        val spilled = assertIs<ItemSet.Spilled>(items)
        assertEquals(3000, spilled.count)
        assertTrue(File(dir, spilled.ref).readLines().size == 3000, "side file holds every id")

        val plan = Plan(
            script = "/home/akiba/.akiba/plans/fx20.kts",
            scriptHash = "sha256:9f2c",
            instance = "test",
            steps = listOf(
                Step(1, StepKind.CreateProject("fx20_batch")),
                Step(2, StepKind.Import(spilled, "fx20_batch", "raw", concurrency = 4), deps = listOf(1)),
            ),
        )
        val text = writer.encode(plan)
        assertTrue(text.length < 2000, "plan stayed small: ${text.length} bytes")
        assertTrue("step-2.ids" in text)
        dir.deleteRecursively()
    }

    @Test
    fun `a small item set is inlined`() {
        val items = writer.items(step = 3, ids = listOf("a", "b"), dir = null)
        val inlined = assertIs<ItemSet.Ids>(items)
        assertEquals(listOf("a", "b"), inlined.ids)
    }

    @Test
    fun `nested groups survive a round trip`() {
        val plan = Plan(
            script = "plan.kts",
            scriptHash = "sha256:aa",
            instance = "test",
            steps = listOf(
                Step(
                    n = 1,
                    kind = StepKind.Group(
                        mode = Mode.PARALLEL,
                        children = listOf(
                            Step(2, StepKind.RunTask("org.iotsplab.akiba.module.AkibaUtils", null, 600)),
                            Step(3, StepKind.RunTask("org.iotsplab.akiba.process.AkibaArch", null, null)),
                        ),
                    ),
                    retry = 2,
                    onFailure = Failure.CONTINUE,
                ),
            ),
        )
        val back = writer.decode(writer.encode(plan))
        assertEquals(plan, back)
    }
}
