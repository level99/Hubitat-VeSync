package support

/**
 * Captures log calls from driver code for assertion.
 *
 * Drivers call `log.info "..."`, `log.debug "..."`, `log.error "..."`, etc.
 * HubitatSpec injects this object as `log` on the driver instance so tests can
 * assert that the right messages were (or were not) emitted.
 *
 * Each level is stored in a separate list in emission order. Specs assert:
 *   testLog.infos.any { it.contains("Power on") }
 *   testLog.errors.isEmpty()
 *
 * All storage is per-level Lists; a `reset()` clears them all.
 */
class TestLog {
    final List<String> infos  = []
    final List<String> debugs = []
    final List<String> errors = []
    final List<String> warns  = []
    final List<String> traces = []

    void info(msg)  { infos  << (msg?.toString() ?: "") }
    void debug(msg) { debugs << (msg?.toString() ?: "") }
    void error(msg) { errors << (msg?.toString() ?: "") }
    void warn(msg)  { warns  << (msg?.toString() ?: "") }
    void trace(msg) { traces << (msg?.toString() ?: "") }

    void reset() {
        infos.clear()
        debugs.clear()
        errors.clear()
        warns.clear()
        traces.clear()
    }

    /** True if any info message contains the given substring. */
    boolean hasInfo(String fragment) {
        infos.any { it.contains(fragment) }
    }

    /** True if any error message contains the given substring. */
    boolean hasError(String fragment) {
        errors.any { it.contains(fragment) }
    }

    /** True if any debug message contains the given substring. */
    boolean hasDebug(String fragment) {
        debugs.any { it.contains(fragment) }
    }
}
