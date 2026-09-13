package local.capturetime.operation

internal object MediaWait {
    const val TIMEOUT_MILLIS = 3_000L

    fun until(clock: () -> Long, sleep: (Long) -> Unit, check: () -> Boolean): Boolean {
        val deadline = clock() + TIMEOUT_MILLIS
        while (true) {
            if (check()) return true
            val remaining = deadline - clock()
            if (remaining <= 0) return false
            sleep(minOf(250L, remaining))
            if (clock() >= deadline) return false
        }
    }
}
