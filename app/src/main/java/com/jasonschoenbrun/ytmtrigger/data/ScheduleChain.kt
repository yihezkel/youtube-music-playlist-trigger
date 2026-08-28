package com.jasonschoenbrun.ytmtrigger.data

/**
 * The "this block follows that one" links between schedules.
 *
 * A schedule with [Schedule.startsAfter] set is never armed from the clock; it
 * starts when the block it names finishes. Motzaei Shabat is why it exists —
 * see the field's own comment.
 *
 * The links used to be followed inline with `firstOrNull { it.startsAfter == id }`
 * in two places, which quietly tolerated things that should not happen: two
 * blocks following the same one (only the first would ever run), a reference to
 * a schedule that had been deleted (the follower simply never runs), and cycles
 * (nothing loops, but every schedule in the ring is silently dead). None of
 * that could be reached while only the generator wrote the field. It becomes
 * reachable the moment the field is editable, so the rules live here, are used
 * on the live path rather than kept for later, and are surfaced by
 * `HealthChecks` so a broken chain is visible instead of just quiet.
 */
object ScheduleChain {

    /** One thing wrong with the links, phrased for a person. */
    data class Problem(val scheduleId: String, val scheduleName: String, val detail: String)

    /** Enabled schedules that follow [id], in stored order. */
    fun followers(all: List<Schedule>, id: String): List<Schedule> =
        all.filter { it.enabled && it.startsAfter == id }

    /**
     * The schedule to start when [id] has finished, or null.
     *
     * When more than one follows the same block, the first is used and the rest
     * are reported by [problems]; starting them all would have them fighting
     * over the speaker.
     */
    fun next(all: List<Schedule>, id: String): Schedule? = followers(all, id).firstOrNull()

    /** The schedule [schedule] follows, enabled or not, or null. */
    fun predecessor(all: List<Schedule>, schedule: Schedule): Schedule? =
        schedule.startsAfter?.let { want -> all.firstOrNull { it.id == want } }

    /**
     * A sentence for the editor: what this schedule waits for, or null when it
     * is an ordinary clock- or calendar-anchored block.
     */
    fun describeFollows(all: List<Schedule>, schedule: Schedule): String? {
        val after = schedule.startsAfter ?: return null
        val p = predecessor(all, schedule)
        return when {
            p == null -> "Starts when another block finishes, but that block no longer exists ($after). It will never run."
            !p.enabled -> "Starts when \"${p.name}\" finishes — but that block is disabled, so it will never run."
            else -> "Starts when \"${p.name}\" finishes, not at a clock time."
        }
    }

    /** Everything wrong with the links across [all]. Empty when healthy. */
    fun problems(all: List<Schedule>): List<Problem> {
        val out = mutableListOf<Problem>()
        val byId = all.associateBy { it.id }

        for (s in all.filter { it.startsAfter != null }) {
            val after = s.startsAfter!!
            when {
                after == s.id ->
                    out += Problem(s.id, s.name, "follows itself, so it can never start")
                byId[after] == null ->
                    out += Problem(s.id, s.name, "follows a schedule that no longer exists")
                s.enabled && byId[after]?.enabled == false ->
                    out += Problem(s.id, s.name, "follows \"${byId[after]!!.name}\", which is disabled")
            }
        }

        // Duplicate followers: reported once per predecessor, naming the ones
        // that will not run rather than the one that will.
        for ((after, group) in all.filter { it.enabled && it.startsAfter != null }.groupBy { it.startsAfter!! }) {
            if (group.size <= 1) continue
            val predName = byId[after]?.name ?: after
            for (loser in group.drop(1)) {
                out += Problem(
                    loser.id, loser.name,
                    "also follows \"$predName\", but \"${group.first().name}\" already does, so this one never starts",
                )
            }
        }

        // Cycles. Walking the links from each schedule must terminate; a repeat
        // means a ring, and every schedule in it is unreachable.
        for (s in all.filter { it.startsAfter != null }) {
            val seen = mutableSetOf(s.id)
            var cur: Schedule? = s
            while (true) {
                val nextId = cur?.startsAfter ?: break
                if (nextId == s.id && seen.size > 1) {
                    out += Problem(s.id, s.name, "is part of a loop of blocks that follow each other, so none of them start")
                    break
                }
                if (!seen.add(nextId)) break
                cur = byId[nextId]
            }
        }
        return out.distinctBy { it.scheduleId to it.detail }
    }
}
