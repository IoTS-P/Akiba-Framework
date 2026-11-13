package org.iotsplab.akiba.plan

/**
 * The plumbing every verb shares.
 *
 * One place decides how a failure becomes an [Outcome], where the timing comes from and
 * what lands in the journal, so a new verb is a single line about what it does rather
 * than a copy of this logic.
 */
internal class ScopeSupport(private val journal: PlanJournal) {

    /** Runs [block], records it, and never lets a [PlanException] escape. */
    fun <T> step(kind: String, args: Map<String, Any?>, block: () -> T): Outcome<T> {
        val started = System.currentTimeMillis()
        val result = try {
            Outcome.Ok(block())
        } catch (e: PlanException) {
            Outcome.Failed(e)
        } catch (e: Exception) {
            // Anything else is a fault of the implementation; surface it as a plan
            // failure so the script decides, but keep the cause.
            Outcome.Failed(PlanException("$kind failed: ${e.message}", cause = e))
        }
        journal.record(kind, args, result, System.currentTimeMillis() - started)
        return result
    }
}
