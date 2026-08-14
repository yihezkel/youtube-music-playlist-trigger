package com.jasonschoenbrun.ytmtrigger.log

/**
 * Temporary instrumentation around speculative fixes in v0.2.0.
 *
 * Every speculative fix calls [start] when it runs and [end] with the outcome.
 * The traces let the next iteration of this app decide:
 * - fix was invoked AND helped (outcome=success rate is high) -> keep the fix,
 *   remove the trace.
 * - fix was never invoked -> the underlying failure mode didn't recur during
 *   the observation window -> consider removing.
 * - fix was invoked but outcome=failure rate is high -> the fix didn't help
 *   -> remove the fix.
 *
 * Grep with: Select-String "EvalFix" on the exported logs.
 */
object EvalFix {
    /** Log that a speculative fix is about to execute. */
    fun start(id: String, fields: Map<String, String> = emptyMap()) {
        Logger.i("EvalFix", "start $id", fields)
    }

    /** Log the outcome of a speculative fix. Always called after [start]. */
    fun end(id: String, success: Boolean, fields: Map<String, String> = emptyMap()) {
        val merged = LinkedHashMap<String, String>(fields.size + 1).apply {
            put("success", success.toString())
            putAll(fields)
        }
        Logger.i("EvalFix", "end $id", merged)
    }

    /** Single-line variant for fixes that produce an immediate boolean outcome. */
    fun once(id: String, success: Boolean, fields: Map<String, String> = emptyMap()) {
        val merged = LinkedHashMap<String, String>(fields.size + 1).apply {
            put("success", success.toString())
            putAll(fields)
        }
        Logger.i("EvalFix", "once $id", merged)
    }
}
