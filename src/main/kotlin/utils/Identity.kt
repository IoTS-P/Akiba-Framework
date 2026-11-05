package org.iotsplab.akiba.utils

/**
 * Name of the single operator account.
 *
 * Akiba has no user management: one password guards the deployment. The name
 * exists only because the daemon credential store is keyed by name, and the
 * first-run setup registers it.
 */
const val ADMIN_USERNAME = "admin"
