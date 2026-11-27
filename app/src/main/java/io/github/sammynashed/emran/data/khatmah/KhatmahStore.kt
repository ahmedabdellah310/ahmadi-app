package io.github.sammynashed.emran.data.khatmah

import android.content.Context
import java.security.SecureRandom

/**
 * Local storage for group khatmahs. Each group is kept as its own share code, so what
 * is saved is exactly what can be sent to friends. Survives app updates; only the user
 * clearing app data removes it.
 *
 * Also remembers which member "I" am in each group (memberId per group).
 */
class KhatmahStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val rng = SecureRandom()

    /** Every group saved on this phone, with my member id in each. */
    fun groups(): List<SavedGroup> = try {
        val ids = prefs.getString(KEY_INDEX, "").orEmpty().split(',').filter { it.isNotBlank() }
        ids.mapNotNull { id -> load(id) }
    } catch (_: Exception) {
        emptyList()
    }

    fun load(groupId: String): SavedGroup? = try {
        val code = prefs.getString(KEY_GROUP + groupId, null) ?: return null
        val group = GroupKhatmahCode.decode(code).getOrNull() ?: return null
        SavedGroup(group, prefs.getString(KEY_ME + groupId, null))
    } catch (_: Exception) {
        null
    }

    /**
     * Creates a new group with me as the first member. Returns the saved group.
     * [name] is the group name, [myName] and [avatar] describe me.
     */
    fun createGroup(name: String, myName: String, avatar: Int, now: Long): SavedGroup {
        val groupId = randomId(8)
        val me = Member(randomId(Member.ID_LENGTH), myName.trim(), avatar)
        val group = GroupKhatmah(groupId, name.trim()).withMember(me)
        save(group, myMemberId = me.id)
        prefs.edit().putString(KEY_SYNC + groupId, KhatmahSync.newKey()).putBoolean(KEY_OWNER + groupId, true).apply()
        return SavedGroup(group, me.id)
    }

    /**
     * Pastes in a code from a friend. If I am not in the group yet, I join it as a new
     * member with [myName] and [avatar]; the updated code then has to be sent back.
     * Returns the merged group, or a failure if the code is bad.
     */
    fun importCode(code: String, myName: String?, avatar: Int?): Result<SavedGroup> = runCatching {
        val incoming = GroupKhatmahCode.decode(code).getOrThrow()
        val existing = load(incoming.id)
        val merged = existing?.group?.merge(incoming) ?: incoming
        var myId = existing?.myMemberId
        var result = merged
        if (myId == null && myName != null && avatar != null) {
            val me = Member(randomId(Member.ID_LENGTH), myName.trim(), avatar)
            result = merged.withMember(me)
            myId = me.id
        }
        save(result, myMemberId = myId)
        // The invite's sync key: from then on the group updates itself (KhatmahSync).
        KhatmahSync.keyOf(code)?.let { k -> if (syncKey(result.id) == null) prefs.edit().putString(KEY_SYNC + result.id, k).apply() }
        SavedGroup(result, myId)
    }

    /** Ticks or unticks [juz] as me. Returns the updated group, or null if I am not in it. */
    fun setJuz(groupId: String, juz: Int, done: Boolean, now: Long): SavedGroup? {
        val saved = load(groupId) ?: return null
        val me = saved.myMemberId ?: return null
        val updated = saved.group.withJuz(juz, done, me, now)
        save(updated, myMemberId = me)
        return SavedGroup(updated, me)
    }

    /** Sets [juz] to [state] as me (start reading, finish, or clear). Null if I am not in the group. */
    fun setState(groupId: String, juz: Int, state: JuzState, now: Long): SavedGroup? {
        val saved = load(groupId) ?: return null
        val me = saved.myMemberId ?: return null
        val updated = saved.group.withState(juz, state, me, now)
        save(updated, myMemberId = me)
        return SavedGroup(updated, me)
    }

    fun delete(groupId: String) {
        val ids = prefs.getString(KEY_INDEX, "").orEmpty().split(',').filter { it.isNotBlank() && it != groupId }
        prefs.edit()
            .remove(KEY_GROUP + groupId)
            .remove(KEY_ME + groupId)
            .remove(KEY_SYNC + groupId).remove(KEY_WRITE + groupId).remove(KEY_UP + groupId).remove(KEY_OWNER + groupId)
            .putString(KEY_INDEX, ids.joinToString(","))
            .apply()
    }

    /** The invite for a group, to copy to friends: the code plus its sync key after '#'. */
    fun shareCode(groupId: String): String? = prefs.getString(KEY_GROUP + groupId, null)?.let { c ->
        syncKey(groupId)?.let { "$c#$it" } ?: c
    }

    /** True for the group I created: only I can end it for everyone. */
    fun isOwner(groupId: String): Boolean = prefs.getBoolean(KEY_OWNER + groupId, false)

    /** The group's sync key (null for groups made before sync, which still work by pasting codes). */
    fun syncKey(groupId: String): String? = prefs.getString(KEY_SYNC + groupId, null)

    /** My secret for writing my own copy on the sync server; made once per group. */
    fun writeSecret(groupId: String): String = prefs.getString(KEY_WRITE + groupId, null) ?: KhatmahSync.newKey().also {
        prefs.edit().putString(KEY_WRITE + groupId, it).apply()
    }

    /** What I last uploaded for a group (its code), so an unchanged group is not sent again. */
    fun lastUpload(groupId: String): String? = prefs.getString(KEY_UP + groupId, null)
    fun setLastUpload(groupId: String, code: String) { prefs.edit().putString(KEY_UP + groupId, code).apply() }

    /** Saves a group merged from friends' copies, keeping who I am. */
    fun replace(group: GroupKhatmah) { save(group, myMemberId = prefs.getString(KEY_ME + group.id, null)) }

    private fun save(group: GroupKhatmah, myMemberId: String?) {
        val ids = prefs.getString(KEY_INDEX, "").orEmpty().split(',').filter { it.isNotBlank() }.toMutableSet()
        ids += group.id
        prefs.edit()
            .putString(KEY_GROUP + group.id, GroupKhatmahCode.encode(group))
            .apply {
                if (myMemberId != null) putString(KEY_ME + group.id, myMemberId)
            }
            .putString(KEY_INDEX, ids.joinToString(","))
            .apply()
    }

    private fun randomId(length: Int): String = buildString {
        repeat(length) { append(ID_CHARS[rng.nextInt(ID_CHARS.length)]) }
    }

    data class SavedGroup(val group: GroupKhatmah, val myMemberId: String?) {
        val me: Member? get() = myMemberId?.let { group.members[it] }
    }

    private companion object {
        const val PREFS = "khatmah_groups"
        const val KEY_INDEX = "index"
        const val KEY_GROUP = "group_"
        const val KEY_ME = "me_"
        const val KEY_SYNC = "sync_"
        const val KEY_WRITE = "write_"
        const val KEY_UP = "up_"
        const val KEY_OWNER = "owner_"
        // Same alphabet as member ids, minus look-alikes, so a group id reads well too.
        const val ID_CHARS = Member.ID_ALPHABET
    }
}
