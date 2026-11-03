// Disabled legacy implementation of the `/task_stages` dynamic server.
//
// It was a DynamicServer ("TaskStageServer", "/task_stages") registered through
// @RouteRequestMethod(POST), tracking TaskStageInfo entries grouped by stage
// set (StageSet: the ordered stage names plus a usingTaskCount) and answering a
// JSON OverallStageMessage with one TaskStageMessage per task (status, current
// stage and name, stage count). Tasks were driven via the @TaskInterface
// methods addTask / addTaskWithId / removeTask / startTask / step / complete.
//
// The commented-out implementation is in git history.
