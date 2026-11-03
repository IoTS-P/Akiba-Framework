// Disabled legacy entry point for the module-side HTTP server.
//
// It started an embedded Ktor/Netty server on the first free port in
// PORT_START..PORT_START+MAX_TRY_COUNT (31778..31793), scanned the permitted
// subclasses of DynamicServer for @RouteRequestMethod and registered each one
// in WorkspaceManager.globalAPIContext, then imported every known task into
// ProgressServer. The live server is org.iotsplab.akiba.server.AkibaServer
// (see server/AkibaServer.kt); this file is kept only as a record of the old
// dynamic-route scheme.
//
// The commented-out implementation is in git history.
