package local.capturetime.time

import java.time.Instant

object PlanningRules {
    fun target(current: Instant, dateAdded: Instant?, filenameTime: Instant?): Instant =
        listOfNotNull(current, dateAdded, filenameTime).minOrNull() ?: current

    fun isCandidate(current: Instant, target: Instant, toleranceSeconds: Long = 0): Boolean =
        target.isBefore(current) && (current.epochSecond - target.epochSecond) > toleranceSeconds
}
