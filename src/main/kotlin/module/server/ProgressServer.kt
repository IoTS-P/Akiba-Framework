// Disabled legacy implementation of the `/progress` dynamic server.
//
// It was a DynamicServer ("ProgressServer", "/progress") registered through
// @RouteRequestMethod(POST), holding in-memory TaskStatus entries (name,
// ongoing, progress, total) and answering a JSON ProgressMessage
// (ongoingCount, totalTaskCount, finished, ongoing[]). Tasks were driven via
// the @TaskInterface methods addTask / removeTask / defineTotal / startTask /
// step / set, and the endpoint looked a task up by `id`.
//
// Progress reporting now goes through
// org.iotsplab.akiba.server.routes.ProgressManager and POST /api/progress.
// The commented-out implementation is in git history.
