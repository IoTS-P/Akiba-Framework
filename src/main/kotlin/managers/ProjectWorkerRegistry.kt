package org.iotsplab.akiba.managers

/**
 * Which sessions are currently attached to which project worker.
 *
 * This is the bookkeeping the switch to per-project workers needs: given a session, find
 * the worker that serves it; given a project, find everyone sharing it. It is deliberately
 * free of process handling so it can be tested, and so [ProjectWorkerPool] stays the only
 * place that starts or stops a worker.
 */
class ProjectWorkerRegistry {

    private val bySession = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val byProject = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /** Binds [sessionId] to [project]; detaches it from whatever project it was on before. */
    @Synchronized
    fun attach(sessionId: String, project: String) {
        bySession.put(sessionId, project)?.let { previous ->
            if (previous != project) byProject[previous]?.remove(sessionId)
        }
        byProject.computeIfAbsent(project) { mutableSetOf() }.add(sessionId)
    }

    /** Detaches [sessionId]; the project keeps serving whoever is left. */
    @Synchronized
    fun detach(sessionId: String) {
        bySession.remove(sessionId)?.let { byProject[it]?.remove(sessionId) }
    }

    fun projectOf(sessionId: String): String? = bySession[sessionId]

    /** Sessions sharing [project], in no particular order. */
    fun sessionsOn(project: String): Set<String> = byProject[project]?.toSet() ?: emptySet()

    /**
     * A project no longer needs its worker when nobody is attached. The pool decides how
     * long to keep it warm; this only answers whether anyone still wants it.
     */
    fun isIdle(project: String): Boolean = sessionsOn(project).isEmpty()

    companion object {
        /**
         * Both topologies coexist during the switch: env `AKIBA_PROJECT_WORKERS=1` opts
         * into one worker per project, anything else keeps the per-turn worker.
         */
        fun poolingEnabled(): Boolean =
            System.getenv("AKIBA_PROJECT_WORKERS")?.trim() in setOf("1", "true", "yes")
    }
}
