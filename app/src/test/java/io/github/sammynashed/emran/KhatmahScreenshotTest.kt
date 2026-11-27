package io.github.sammynashed.emran

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.sammynashed.emran.data.khatmah.GroupKhatmahCode
import io.github.sammynashed.emran.data.khatmah.KhatmahStore
import io.github.sammynashed.emran.data.khatmah.Member
import io.github.sammynashed.emran.ui.screens.GroupKhatmahDetailScreen
import io.github.sammynashed.emran.ui.screens.GroupKhatmahListScreen
import io.github.sammynashed.emran.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the group khatmah screens to src/test/screenshots/khatmah_*.png for design review (not a golden test). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class KhatmahScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun seed(ar: Boolean = false): String {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("khatmah_groups", Context.MODE_PRIVATE).edit().clear().commit()
        val store = KhatmahStore(ctx)
        val now = 1_763_380_000_000L
        val mine = store.createGroup(if (ar) "ختمة العائلة" else "Quran Buddies", if (ar) "سامي" else "Sammy", 0, now)
        var g = GroupKhatmahCode.decode(store.shareCode(mine.group.id)!!).getOrThrow()
        val sara = Member("SARA2", if (ar) "سارة" else "Sara", 5)
        val omar = Member("OMAR3", if (ar) "عمر" else "Omar", 3)
        val lama = Member("LAMA4", if (ar) "لمى" else "Lama", 7)
        g = g.withMember(sara).withMember(omar).withMember(lama)
        val who = listOf(sara.id, omar.id, lama.id, mine.myMemberId!!)
        val today = System.currentTimeMillis()
        listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 15, 16, 18, 21, 22, 25, 26, 28).forEachIndexed { i, juz ->
            g = g.withJuz(juz, true, who[i % who.size], today - (20 - i) * 6 * 3_600_000L)
        }
        g = g.withState(17, io.github.sammynashed.emran.data.khatmah.JuzState.READING, sara.id, today)
            .withState(19, io.github.sammynashed.emran.data.khatmah.JuzState.READING, mine.myMemberId!!, today)
        store.importCode(GroupKhatmahCode.encode(g), null, null).getOrThrow()
        return mine.group.id
    }

    private fun clear() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("khatmah_groups", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun shot(name: String) = rule.onRoot().captureRoboImage("src/test/screenshots/khatmah_$name.png")

    @Test fun garden_en() {
        val id = seed()
        rule.setContent { MyApplicationTheme { GroupKhatmahDetailScreen("en", id, onBack = {}) } }
        rule.mainClock.advanceTimeBy(2000); shot("garden_en")
    }

    @Config(qualifiers = "w412dp-h2000dp-xxhdpi")
    @Test fun garden_en_full() {
        val id = seed()
        rule.setContent { MyApplicationTheme { GroupKhatmahDetailScreen("en", id, onBack = {}) } }
        rule.mainClock.advanceTimeBy(2000); shot("garden_en_full")
    }

    @Config(qualifiers = "w412dp-h2000dp-xxhdpi")
    @Test fun garden_ar_full() {
        val id = seed(ar = true)
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MyApplicationTheme { GroupKhatmahDetailScreen("ar", id, onBack = {}) }
            }
        }
        rule.mainClock.advanceTimeBy(2000); shot("garden_ar_full")
    }

    @Config(qualifiers = "w412dp-h1400dp-xxhdpi")
    @Test fun friends_and_stats() {
        val id = seed()
        rule.setContent { MyApplicationTheme { GroupKhatmahDetailScreen("en", id, onBack = {}) } }
        rule.onNodeWithText("Friends").performClick(); rule.mainClock.advanceTimeBy(1500); shot("friends")
        rule.onNodeWithText("Stats").performClick(); rule.mainClock.advanceTimeBy(1500); shot("stats")
    }

    @Test fun juz_flow() {
        val id = seed()
        rule.setContent { MyApplicationTheme { GroupKhatmahDetailScreen("en", id, onBack = {}) } }
        rule.mainClock.advanceTimeBy(1500)
        rule.onNodeWithText("Garden").performClick(); rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText("14").performClick(); rule.mainClock.advanceTimeBy(1200); shot("juz_none")
        rule.onNodeWithText("Start reading").performClick(); rule.mainClock.advanceTimeBy(1200); shot("juz_reading")
        rule.onNodeWithText("Mark as complete").performClick(); rule.mainClock.advanceTimeBy(1500); shot("celebration")
    }

    @Test fun welcome_and_friends_list() {
        clear()
        rule.setContent { MyApplicationTheme { GroupKhatmahListScreen("en", {}, {}) } }
        rule.mainClock.advanceTimeBy(800); shot("welcome")
        rule.onNodeWithText("Get started").performClick(); rule.mainClock.advanceTimeBy(800); shot("start")
        rule.onNodeWithText("Create group").performClick(); rule.mainClock.advanceTimeBy(800); shot("create_sheet")
    }

    @Config(qualifiers = "w412dp-h1400dp-xxhdpi")
    @Test fun list_with_group() {
        seed()
        rule.setContent { MyApplicationTheme { GroupKhatmahListScreen("en", {}, {}) } }
        rule.mainClock.advanceTimeBy(800); shot("list")
    }
}
