package io.github.sammynashed.emran.data.khatmah

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupKhatmahTest {

    private val ali = Member("AB123", "Ali", 3)
    private val sara = Member("CD345", "Sara", 7)

    private fun group(vararg steps: Triple<Int, Boolean, Long>, by: String = ali.id): GroupKhatmah {
        var g = GroupKhatmah("g1", "Family").withMember(ali).withMember(sara)
        for ((juz, done, at) in steps) g = g.withJuz(juz, done, by, at)
        return g
    }

    @Test
    fun merge_newestWinsPerJuz() {
        val a = group(Triple(1, true, 100L), Triple(2, true, 500L))
        val b = group(Triple(1, false, 200L), Triple(2, true, 300L), Triple(3, true, 50L), by = sara.id)
        val m = a.merge(b)
        assertFalse("juz 1 was unticked later on b", m.isDone(1))
        assertTrue("juz 2 ticked later on a", m.isDone(2))
        assertTrue("juz 3 only exists on b", m.isDone(3))
        assertEquals(2, m.doneCount)
    }

    @Test
    fun merge_isCommutativeAndIdempotent() {
        val a = group(Triple(1, true, 100L), Triple(4, true, 900L))
        val b = group(Triple(1, false, 200L), Triple(5, true, 10L), by = sara.id)
        assertEquals(a.merge(b).marks, b.merge(a).marks)
        assertEquals(a.merge(b).marks, a.merge(b).merge(b).marks)
    }

    @Test
    fun merge_unitesMembers_oursWinOnSameId() {
        val a = GroupKhatmah("g1", "Family").withMember(ali)
        val b = GroupKhatmah("g1", "Family").withMember(sara).withMember(Member("AB123", "Ali (old)", 0))
        val m = a.merge(b)
        assertEquals(setOf("AB123", "CD345"), m.members.keys)
        assertEquals("Ali", m.members.getValue("AB123").name)
    }

    @Test
    fun sameNameDifferentIds_stayDistinct() {
        val a = GroupKhatmah("g1", "Family").withMember(Member("AB123", "Ali", 3))
        val b = a.withMember(Member("ZZ999", "Ali", 3))
        assertEquals(2, b.members.size)
    }

    @Test
    fun tieBreakIsDeterministic() {
        val x = JuzMark(done = true, by = "AB123", at = 10L)
        val y = JuzMark(done = false, by = "AB123", at = 10L)
        assertEquals(x, GroupKhatmah.newest(x, y))
        assertEquals(x, GroupKhatmah.newest(y, x))
    }

    @Test
    fun merge_refusesDifferentGroups() {
        val a = GroupKhatmah("g1", "A")
        val b = GroupKhatmah("g2", "B")
        assertTrue(runCatching { a.merge(b) }.isFailure)
    }

    @Test
    fun code_roundTrips_includingArabicAndSeparators() {
        val tricky = Member("EF567", "أحمد | ~ ,\nنعم", 11)
        val g = GroupKhatmah("abc-123", "ختمة | العائلة ~ 1")
            .withMember(tricky)
            .withJuz(1, true, tricky.id, 1_700_000_000_000L)
            .withJuz(30, false, tricky.id, 1_700_000_005_000L)
        val code = GroupKhatmahCode.encode(g)
        assertEquals(g, GroupKhatmahCode.decode(code).getOrThrow())
    }

    @Test
    fun code_detectsDamage() {
        val code = GroupKhatmahCode.encode(group(Triple(7, true, 1L)))
        assertTrue(GroupKhatmahCode.decode(code.replace("7|1|1", "7|1|2")).isFailure)
        assertTrue(GroupKhatmahCode.decode(code.dropLast(3)).isFailure)
        assertTrue(GroupKhatmahCode.decode("hello").isFailure)
    }

    @Test
    fun complete_whenAll30Done() {
        var g = GroupKhatmah("g", "All").withMember(ali)
        for (j in 1..30) g = g.withJuz(j, true, ali.id, j.toLong())
        assertTrue(g.isComplete)
    }

    @Test(expected = IllegalArgumentException::class)
    fun member_rejectsBadAvatar() {
        Member("AB123", "X", 12)
    }

    @Test
    fun displayNames_onlyAddIdOnClash() {
        val g = GroupKhatmah("g1", "F")
            .withMember(Member("AB123", "Ali", 1))
            .withMember(Member("ZZ999", " ali ", 2))
            .withMember(Member("CD345", "Sara", 3))
        val names = g.displayNames()
        assertEquals("Ali · AB123", names["AB123"])
        assertEquals(" ali  · ZZ999", names["ZZ999"])
        assertEquals("Sara", names["CD345"])
    }

    @Test
    fun doneBy_countsOnlyDoneTicksOfThatMember() {
        val g = group(Triple(1, true, 1L), Triple(2, true, 2L), Triple(3, false, 3L))
        assertEquals(2, g.doneBy(ali.id))
        assertEquals(0, g.doneBy(sara.id))
    }

    @Test
    fun reading_roundTripsAndMergesNewestWins() {
        val a = group().withState(4, JuzState.READING, ali.id, 10L)
        val back = GroupKhatmahCode.decode(GroupKhatmahCode.encode(a)).getOrThrow()
        assertEquals(JuzState.READING, back.state(4))
        assertEquals(4, back.readingBy(ali.id))
        val b = group().withState(4, JuzState.DONE, sara.id, 20L)
        assertEquals(JuzState.DONE, a.merge(b).state(4))
        assertEquals(1, a.readingCount)
    }
}
