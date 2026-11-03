package org.iotsplab.akiba.llm.agent.runtime

import org.apache.logging.log4j.LogManager
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.llm.agent.template.AgentTemplateRegistry
import org.iotsplab.akiba.llm.agent.template.CancellationPolicy

// ============================================================
//  DefaultStateHook — gatekeeper enforcing the ancestor-only
//  cancellation policy (plus per-template overrides)
// ============================================================
//
// Registered automatically by [AgentRuntime] on construction; it only
// inspects CANCELLING transitions and allows every other state change.
// System callers (`requesterSessionId == null`), self-cancels and
// ancestors are allowed — ancestors subject to the target's
// [InteractionPolicy.canBeCancelledBy], where `NONE` denies and the
// default [CancellationPolicy.ANCESTOR_ONLY] allows; everyone else
// (siblings, uncles, descendants, peers) is denied unless the target
// opts into [CancellationPolicy.ANY].  A denied request leaves the
// target on its current state, mirrors that to the DB and returns a
// structured "denied" outcome to the caller — the affected agent is
// deliberately not signalled.

/**
 * Production default hook.  Constructed and registered by [AgentRuntime]
 * automatically.
 *
 * @param agentDbClient used to walk `parent_session_id` chains to test
 *   ancestry, and to look up the target's [CancellationPolicy].  All reads
 *   are best-effort: a transient DB error is logged and the transition is
 *   treated as DENY (conservative).
 */
class DefaultStateHook(
    private val agentDbClient: AgentDatabaseClient,
    private val parentChainCache: ParentChainCache = ParentChainCache(),
) : RuntimeStateHook {
    private val logger = LogManager.getLogger(DefaultStateHook::class.java)

    override fun onStateTransition(transition: StateTransition): StateTransitionDecision {
        // Only gate CANCELLING transitions.  All other state
        // changes (running, standby, msghandle, error, closed)
        // are internal lifecycle moves; gating them would break
        // the dispatcher's "STANDBY → MSGHANDLE on mailbox" path.
        if (transition.next != RuntimeState.CANCELLING) {
            return StateTransitionDecision.ALLOW
        }

        // 1. System-level caller → always allowed.
        val requester = transition.requesterSessionId
            ?: return StateTransitionDecision.ALLOW

        // 2. Self-cancel → always allowed.
        if (requester == transition.sessionId) {
            return StateTransitionDecision.ALLOW
        }

        // 3. Ancestor?  Walk the parent chain from requester to the root;
        //    seeing the target on the way means requester IS an ancestor.
        val isAncestor = try {
            parentChainCache.isAncestor(
                agentDbClient = agentDbClient,
                potentialAncestor = requester,
                target = transition.sessionId,
            )
        } catch (e: Exception) {
            logger.warn(
                "DefaultStateHook: ancestry lookup failed for requester=$requester " +
                    "target=${transition.sessionId}: ${e.message}",
            )
            return StateTransitionDecision.DENY
        }

        if (isAncestor) {
            // Honour the target's policy.
            val policy = lookupPolicy(transition.sessionId)
            if (policy == CancellationPolicy.NONE) {
                logger.info(
                    "DefaultStateHook: denying CANCELLING on session=${transition.sessionId} " +
                        "by ancestor requester=$requester (canBeCancelledBy=NONE)"
                )
                return StateTransitionDecision.DENY
            }
            return StateTransitionDecision.ALLOW
        }

        // 4. Non-ancestor (sibling / uncle / descendant / unrelated).
        //    canBeCancelledBy=ANY opts in to allowing them.
        val policy = lookupPolicy(transition.sessionId)
        if (policy == CancellationPolicy.ANY) {
            return StateTransitionDecision.ALLOW
        }
        logger.info(
            "DefaultStateHook: denying CANCELLING on session=${transition.sessionId} " +
                "by non-ancestor requester=$requester (canBeCancelledBy=${policy.name})"
        )
        return StateTransitionDecision.DENY
    }

    /**
     * Look up the [CancellationPolicy] declared by the target's template,
     * falling back to [CancellationPolicy.ANCESTOR_ONLY] (the
     * template-declared default) for freeform / ad-hoc sessions.  The
     * lookup is best-effort: any error falls back to the safe default.
     *
     * Templates resolve via
     * [AgentTemplateRegistry.findPolicyForSession], which scans registered
     * templates for a matching factory context — the SessionInfo alone
     * carries binaryId/moduleName but not the template id.
     */
    private fun lookupPolicy(targetSessionId: String): CancellationPolicy {
        return try {
            AgentTemplateRegistry.findPolicyForSession(agentDbClient, targetSessionId)
        } catch (_: Exception) {
            CancellationPolicy.ANCESTOR_ONLY
        }
    }
}

/**
 * Small cache for parent-chain walks.  The cache keys on
 * `(potentialAncestor, target)` and stores the boolean answer
 * for a short TTL so repeated hook evaluations in the same
 * tick do not hammer the DB.
 */
class ParentChainCache(
    private val ttlMs: Long = 5_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class Key(val ancestor: String, val target: String)
    private data class Entry(val isAncestor: Boolean, val expiresAt: Long)

    private val cache = java.util.concurrent.ConcurrentHashMap<Key, Entry>()

    fun isAncestor(
        agentDbClient: AgentDatabaseClient,
        potentialAncestor: String,
        target: String,
    ): Boolean {
        // Trivial: a session is its own ancestor for self-cancel,
        // but the hook checks that case directly before calling.
        // Here we only care about *strict* ancestry.
        if (potentialAncestor == target) return true

        val key = Key(potentialAncestor, target)
        val now = clock()
        cache[key]?.let { entry ->
            if (entry.expiresAt > now) return entry.isAncestor
        }

        // Walk from `target` upward.  If we ever see
        // `potentialAncestor` in the chain, it's an ancestor.
        var cursor: String? = try {
            agentDbClient.getSession(target).parentSessionId
        } catch (_: Exception) {
            null
        }
        var hops = 0
        val maxHops = 1024  // defensive: guard against cycles
        while (cursor != null && hops < maxHops) {
            if (cursor == potentialAncestor) {
                cache[key] = Entry(true, now + ttlMs)
                return true
            }
            cursor = try {
                agentDbClient.getSession(cursor).parentSessionId
            } catch (_: Exception) {
                null
            }
            hops++
        }
        cache[key] = Entry(false, now + ttlMs)
        return false
    }

    fun clear() = cache.clear()
}
