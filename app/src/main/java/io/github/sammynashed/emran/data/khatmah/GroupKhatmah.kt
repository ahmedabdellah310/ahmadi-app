package io.github.sammynashed.emran.data.khatmah

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.zip.CRC32

/**
 * A friends' khatmah: each of the 30 juz is ticked by a member who finished it.
 *
 * Offline-first, no server. The whole group travels as a share code (copy/paste).
 * When two copies meet, [merge] keeps, per juz, the mark with the newest [JuzMark.at].
 * Unticking is stored as a mark with done = false, so an undo also wins over an older tick.
 *
 * A member = display name + built-in avatar (0..[AVATAR_COUNT]-1) + a short random id.
 * The id is what tells apart two people who picked the same name and avatar.
 *
 * Pure Kotlin on purpose: no Android imports, so it is unit-testable on the JVM.
 */
data class Member(
    /** Short random id, [ID_LENGTH] chars from [ID_ALPHABET]. Never changes. */
    val id: String,
    val name: String,
    /** Index into the built-in avatar set, 0 until [AVATAR_COUNT]. */
    val avatar: Int,
) {
    init {
        require(id.length == ID_LENGTH) { "bad member id" }
        require(avatar in 0 until AVATAR_COUNT) { "avatar out of range: $avatar" }
    }

    companion object {
        const val AVATAR_COUNT = 12
        const val ID_LENGTH = 5
        const val ID_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    }
}

/** Where a juz is: nobody on it, someone reading it, or finished. */
enum class JuzState { NONE, READING, DONE }

data class JuzMark(
    val done: Boolean,
    /** [Member.id] of whoever changed this juz. */
    val by: String,
    /** Epoch millis of the change. The newest one wins in a merge. */
    val at: Long,
    /** Someone started reading it (only meaningful when not [done]). */
    val reading: Boolean = false,
) {
    val state: JuzState get() = if (done) JuzState.DONE else if (reading) JuzState.READING else JuzState.NONE
}

data class GroupKhatmah(
    val id: String,
    val name: String,
    /** Key = [Member.id]. */
    val members: Map<String, Member> = emptyMap(),
    /** Key = juz number 1..30. A missing key means "never touched". */
    val marks: Map<Int, JuzMark> = emptyMap(),
) {
    val doneCount: Int get() = marks.count { (juz, m) -> juz in JUZ_RANGE && m.done }

    val isComplete: Boolean get() = doneCount == JUZ_COUNT

    fun isDone(juz: Int): Boolean = marks[juz]?.done == true

    fun state(juz: Int): JuzState = marks[juz]?.state ?: JuzState.NONE

    val readingCount: Int get() = marks.count { (juz, m) -> juz in JUZ_RANGE && m.state == JuzState.READING }

    /** The juz [memberId] is reading now (the lowest if several), or null. */
    fun readingBy(memberId: String): Int? = marks.filter { it.value.state == JuzState.READING && it.value.by == memberId }.keys.minOrNull()

    /** Returns a copy with [juz] set to [state], stamped with [memberId] and [now]. */
    fun withState(juz: Int, state: JuzState, memberId: String, now: Long): GroupKhatmah {
        require(juz in JUZ_RANGE) { "juz out of range: $juz" }
        return copy(marks = marks + (juz to JuzMark(state == JuzState.DONE, memberId, now, reading = state == JuzState.READING)))
    }

    /** Juz ticked by [memberId] (currently done). */
    fun doneBy(memberId: String): Int = marks.count { (juz, m) -> juz in JUZ_RANGE && m.done && m.by == memberId }

    /**
     * What to show as each member's name. Usually just the name; when two members share a
     * name (case and spacing ignored), each of them gets " · <id>" so they can be told apart.
     */
    fun displayNames(): Map<String, String> {
        val counts = members.values.groupingBy { it.name.trim().lowercase() }.eachCount()
        return members.values.associate { m ->
            val clash = (counts[m.name.trim().lowercase()] ?: 0) > 1
            m.id to if (clash) "${m.name} · ${m.id}" else m.name
        }
    }

    fun withMember(member: Member): GroupKhatmah = copy(members = members + (member.id to member))

    /** Returns a copy with [juz] set to [done], stamped with [memberId] and [now]. */
    fun withJuz(juz: Int, done: Boolean, memberId: String, now: Long): GroupKhatmah {
        require(juz in JUZ_RANGE) { "juz out of range: $juz" }
        return copy(marks = marks + (juz to JuzMark(done, memberId, now)))
    }

    /** Per-juz newest-wins merge; members are united. Both sides must be the same group. */
    fun merge(other: GroupKhatmah): GroupKhatmah {
        require(other.id == id) { "different groups: $id vs ${other.id}" }
        val keys = marks.keys + other.marks.keys
        val mergedMarks = keys.associateWith { juz ->
            val mine = marks[juz]
            val theirs = other.marks[juz]
            when {
                mine == null -> theirs!!
                theirs == null -> mine
                else -> newest(mine, theirs)
            }
        }
        val mergedMembers = other.members + members // ours win on the same id
        val mergedName = if (name.isBlank()) other.name else name
        return GroupKhatmah(id, mergedName, mergedMembers, mergedMarks)
    }

    companion object {
        const val JUZ_COUNT = 30
        val JUZ_RANGE = 1..JUZ_COUNT

        /** Deterministic tie-break: later time wins; on equal time, done beats undone, then member id. */
        internal fun newest(a: JuzMark, b: JuzMark): JuzMark {
            if (a.at != b.at) return if (a.at > b.at) a else b
            if (a.done != b.done) return if (a.done) a else b
            return if (a.by >= b.by) a else b
        }
    }
}

/**
 * Share code format:  `EK1:<body>~<crc32 hex, 8 chars>`
 * body = records joined by '\n'; a record = fields joined by '|'. Text values are
 * URL-encoded, so raw '\n' '|' '~' never appear inside a value. Records:
 *   g|<groupId>|<groupName>                 (always first)
 *   m|<memberId>|<memberName>|<avatar>      (one per member)
 *   j|<juz>|<state 0 none, 1 done, 2 reading>|<at>|<memberId>      (one per juz with a mark)
 * The CRC catches a mistyped or half-pasted code before it can corrupt a group.
 */
object GroupKhatmahCode {
    private const val PREFIX = "EK1:"

    fun encode(group: GroupKhatmah): String {
        val records = mutableListOf("g|${enc(group.id)}|${enc(group.name)}")
        for (m in group.members.values.sortedBy { it.id }) {
            records += "m|${enc(m.id)}|${enc(m.name)}|${m.avatar}"
        }
        for (juz in group.marks.keys.sorted()) {
            val m = group.marks.getValue(juz)
            records += "j|$juz|${when (m.state) { JuzState.DONE -> 1; JuzState.READING -> 2; JuzState.NONE -> 0 }}|${m.at}|${enc(m.by)}"
        }
        val body = records.joinToString("\n")
        return PREFIX + body + "~" + crc(body)
    }

    /** Returns the group, or a failure saying why the code is not usable. */
    fun decode(code: String): Result<GroupKhatmah> = runCatching {
        // An invite carries the group's sync key after '#' (KhatmahSync); the code itself is what comes before it.
        val trimmed = code.trim().substringBefore('#').trim()
        require(trimmed.startsWith(PREFIX)) { "not a khatmah code" }
        val rest = trimmed.removePrefix(PREFIX)
        val cut = rest.lastIndexOf('~')
        require(cut >= 0) { "code is cut off" }
        val body = rest.substring(0, cut)
        require(rest.substring(cut + 1).equals(crc(body), ignoreCase = true)) {
            "code is damaged: check it was copied in full"
        }
        // Records split on '\n', fields on '|'. Raw '\n' and '|' never occur inside a value (URL-encoded).
        val records = body.split('\n')
        val head = records.firstOrNull()?.split('|') ?: error("empty code")
        require(head.size == 3 && head[0] == "g") { "code has no group" }
        val id = dec(head[1])
        val name = dec(head[2])
        val members = HashMap<String, Member>()
        val marks = HashMap<Int, JuzMark>()
        for (f in records.drop(1)) {
            val v = f.split('|')
            when (v[0]) {
                "m" -> {
                    require(v.size == 4) { "bad member entry" }
                    val m = Member(dec(v[1]), dec(v[2]), v[3].toInt())
                    members[m.id] = m
                }
                "j" -> {
                    require(v.size == 5) { "bad juz entry" }
                    val juz = v[1].toInt()
                    require(juz in GroupKhatmah.JUZ_RANGE) { "juz out of range: $juz" }
                    marks[juz] = JuzMark(done = v[2] == "1", at = v[3].toLong(), by = dec(v[4]), reading = v[2] == "2")
                }
                else -> error("unknown entry")
            }
        }
        GroupKhatmah(id, name, members, marks)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String): String = URLDecoder.decode(s, "UTF-8")

    private fun crc(s: String): String {
        val c = CRC32()
        c.update(s.toByteArray(Charsets.UTF_8))
        return "%08x".format(c.value)
    }
}
