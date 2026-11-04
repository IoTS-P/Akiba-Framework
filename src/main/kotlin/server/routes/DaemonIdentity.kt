package org.iotsplab.akiba.server.routes

import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.iotsplab.akiba.data.database.AgentDatabaseClient
import org.iotsplab.akiba.data.database.DatabaseClient
import org.iotsplab.akiba.llm.agent.runtime.AgentSessionReconciler

private val logger: Logger = LogManager.getLogger("DaemonIdentity")

/**
 * The daemon identity a web user acts as.
 *
 * There is no built-in service account: an instance is owned by exactly one
 * daemon username, and the server speaks to the daemon *as the signed-in user*.
 *
 * [secret] is a derived, single-purpose credential minted by the daemon
 * (`/instance/worker-grant`), not the account password: it can expire on its
 * own and never leaks into generated child-process configs.
 */
data class DaemonIdentity(
    val username: String,
    val secret: String,
    val isAdmin: Boolean,
    val createdAt: Instant = Instant.now(),
)

/**
 * In-memory registry of daemon identities, keyed by username, plus the
 * instance → owner mapping needed to resolve credentials from an instance name.
 *
 * Per-request daemon sessions are created all over the route layer and almost
 * all of them only know the instance name; since an instance has exactly one
 * owner, that name is enough to find the identity to authenticate with.
 *
 * Rebuilt on every sign-in, so losing it on restart is safe.
 */
object DaemonIdentities {
    private val byUser = ConcurrentHashMap<String, DaemonIdentity>()
    private val ownerByInstance = ConcurrentHashMap<String, String>()

    fun register(identity: DaemonIdentity) {
        byUser[identity.username] = identity
        logger.debug("Registered daemon identity for '${identity.username}'")
    }

    fun forUser(username: String): DaemonIdentity? = byUser[username]

    fun ownerOf(instanceName: String): String? = ownerByInstance[instanceName]

    /** Record that [instances] belong to [username]. */
    fun registerInstances(username: String, instances: Collection<String>) {
        instances.forEach { ownerByInstance[it] = username }
    }

    fun forgetInstance(instanceName: String) {
        ownerByInstance.remove(instanceName)
    }

    /** Drop the credentials of [username] (used when the daemon rejects them). */
    fun invalidate(username: String) {
        byUser.remove(username)
        ownerByInstance.entries.removeIf { it.value == username }
        logger.info("Invalidated cached daemon identity for '$username'")
    }

    fun registeredUsers(): Set<String> = byUser.keys.toSet()

    /** Names of the instances known to belong to [username]. */
    fun instancesOf(username: String): List<String> =
        ownerByInstance.entries.filter { it.value == username }.map { it.key }
}

/**
 * Apply a new password to an existing daemon credential, on behalf of an
 * administrator.
 *
 * Signing in as the administrator yields a token, and the daemon only accepts
 * this operation from an administrator token — which is why the admin's own
 * [DaemonIdentity] (established at their sign-in) is required here.
 *
 * @throws DatabaseClient.DatabaseDaemonException when the daemon refuses (for
 *         instance, when the administrator's own credential is not flagged as an
 *         administrator in the daemon's store).
 */
fun applyDaemonPassword(
    daemonHost: String,
    daemonPort: Int,
    admin: DaemonIdentity,
    username: String,
    newPassword: String,
) {
    val client = DatabaseClient(daemonHost, daemonPort)
    try {
        client.login(admin.username, admin.secret)
        val updated = client.setUserPassword(username, newPassword)
        if (!updated) {
            // The daemon has no record of the account yet (for example an account
            // that never signed in). Creating it is the correct repair.
            client.registerUser(username, newPassword, isAdmin = false)
        }
    } finally {
        runCatching { client.logout() }
    }
}

/**
 * Lift an account's brute-force lock on the daemon side.
 *
 * The daemon keeps its own credentials and its own failure counters (see
 * `UserStore.login`), so the framework's administrator action has to be mirrored
 * here — otherwise an account unlocked on the web would still be refused by the
 * daemon for the rest of the lock window.
 *
 * @throws DatabaseClient.DatabaseDaemonException when the daemon refuses.
 */
fun applyDaemonUnlock(
    daemonHost: String,
    daemonPort: Int,
    admin: DaemonIdentity,
    username: String,
) {
    val client = DatabaseClient(daemonHost, daemonPort)
    try {
        client.login(admin.username, admin.secret)
        client.unlockUser(username)
    } finally {
        runCatching { client.logout() }
    }
}

private val reconcileScope = CoroutineScope(Dispatchers.IO)

/**
 * Best-effort agent-session reconciliation for [instances], run in the
 * background right after a successful sign-in.
 *
 * Reconciliation marks session rows left in a non-terminal state by a previous
 * crash as closed, so the UI stops showing a frozen "Running" pill. It has to
 * run as the instance's owner — hence it is triggered per sign-in rather than
 * once at server start with a shared account — and it must never delay or fail
 * a login, so it runs on a background scope and swallows per-instance errors.
 */
fun reconcileUserInstances(
    daemonHost: String,
    daemonPort: Int,
    identity: DaemonIdentity,
    instances: List<String>,
    reasonTag: String,
) {
    if (instances.isEmpty()) return
    reconcileScope.launch {
        for (instance in instances) {
            val client = DatabaseClient(daemonHost, daemonPort)
            try {
                client.login(identity.username, identity.secret)
                client.connectToInstance(instance)
                try {
                    val report = AgentSessionReconciler(
                        agentDbClient = AgentDatabaseClient(client),
                        reasonTag = reasonTag,
                    ).reconcile()
                    logger.info(
                        "Reconciled instance '$instance' ($reasonTag): scanned=${report.scanned}, " +
                            "reconciled=${report.reconciled}, failed=${report.failed}"
                    )
                } finally {
                    runCatching { client.disconnectToInstance(instance) }
                }
            } catch (e: Exception) {
                logger.warn("Reconciliation of instance '$instance' failed: ${e.message}")
            } finally {
                runCatching { client.logout() }
            }
        }
    }
}

/**
 * Authenticate as [username] against the daemon and remember the resulting
 * identity.
 *
 * The web layer must already have verified [password] against its own user
 * table — this function only mirrors that identity into the daemon, which keeps
 * its own credential store. When the daemon has no record for the user yet (a
 * pre-existing deployment, or an account created before authentication
 * existed) the credential is created on the spot; the password is proven by the
 * caller, so this is not an unauthenticated bootstrap.
 *
 * @throws DatabaseClient.DatabaseDaemonException when the daemon is unreachable,
 *         or when it holds a *different* password for this user (credential
 *         drift — an administrator has to recreate the account).
 */
fun establishDaemonIdentity(
    daemonHost: String,
    daemonPort: Int,
    username: String,
    password: String,
    isAdmin: Boolean,
): DaemonIdentity {
    val client = DatabaseClient(daemonHost, daemonPort)
    val info = try {
        client.loginDetailed(username, password)
    } catch (e: DatabaseClient.DatabaseDaemonException) {
        if (e.statusCode != HttpStatusCode.Unauthorized) throw e

        // Unknown username *or* wrong password — the daemon cannot tell the two
        // apart by design. Try to create the credential: it succeeds only when
        // the username is free (a new account), and reports `false` when the
        // user already exists with a different password.
        logger.info(
            "Daemon rejected credentials for '$username'; attempting to register the " +
                "credential (this is expected for accounts created before the daemon " +
                "started checking passwords)"
        )
        val created = try {
            client.registerUser(username, password, isAdmin = isAdmin)
        } catch (e: DatabaseClient.DatabaseDaemonException) {
            // Registering an *administrator* needs an authorizing administrator
            // token, which this path does not have when the credential store was
            // already populated. Fall back to a plain credential: it can still
            // own and use instances, which is what the account needs day to day.
            if (e.statusCode == HttpStatusCode.Unauthorized && isAdmin) {
                logger.warn(
                    "Could not register '$username' as a daemon administrator " +
                        "(no administrator token available); registering a plain credential"
                )
                client.registerUser(username, password, isAdmin = false)
            } else {
                throw e
            }
        }
        if (!created) {
            throw DatabaseClient.DatabaseDaemonException(
                HttpStatusCode.Unauthorized,
                "The database daemon holds a different password for '$username'. " +
                    "Ask an administrator to recreate this account."
            )
        }
        client.loginDetailed(username, password)
    }

    val secret = client.grantWorkerCredential("server-session")

    // Resolve which instances this user owns, so instance-scoped routes can find
    // the identity to authenticate with later on.
    val instances = try {
        client.listInstances().map { it.name }
    } catch (e: Exception) {
        logger.warn("Could not list instances of '$username': ${e.message}")
        emptyList()
    }
    runCatching { client.logout() }

    val identity = DaemonIdentity(
        username = info.username,
        secret = secret,
        isAdmin = info.isAdmin,
    )
    DaemonIdentities.register(identity)
    DaemonIdentities.registerInstances(identity.username, instances)
    logger.info(
        "Daemon identity established for '${identity.username}' " +
            "(isAdmin=${identity.isAdmin}, instances=${instances.size})"
    )
    reconcileUserInstances(daemonHost, daemonPort, identity, instances, "signin")
    return identity
}
