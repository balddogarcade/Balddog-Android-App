package dog.bald.arcade.diagnostics

import com.google.firebase.analytics.FirebaseAnalytics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnalyticsHelperTest {
    private val captured = mutableListOf<Pair<String, Map<String, Any>>>()

    @Before
    fun setUp() {
        captured.clear()
        AnalyticsHelper.testSink =
            AnalyticsSink { name, params ->
                captured.add(name to params)
            }
    }

    @After
    fun tearDown() {
        AnalyticsHelper.testSink = null
    }

    @Test
    fun `sanitizeParam trims blanks and truncates to 100 chars`() {
        assertEquals("unknown", AnalyticsHelper.sanitizeParam("   "))
        assertEquals("unknown", AnalyticsHelper.sanitizeParam(null))
        val longStr = "a".repeat(150)
        assertEquals(100, AnalyticsHelper.sanitizeParam(longStr).length)
    }

    @Test
    fun `logScreenView emits screen_view with sanitized name`() {
        AnalyticsHelper.logScreenView("tab_catalog", "MainShellScreen")
        assertEquals(1, captured.size)
        val (name, params) = captured.first()
        assertEquals(FirebaseAnalytics.Event.SCREEN_VIEW, name)
        assertEquals("tab_catalog", params[FirebaseAnalytics.Param.SCREEN_NAME])
        assertEquals("MainShellScreen", params[FirebaseAnalytics.Param.SCREEN_CLASS])
    }

    @Test
    fun `logAuthEvent emits login or sign_up with method`() {
        AnalyticsHelper.logAuthEvent("google", isSignUp = false)
        AnalyticsHelper.logAuthEvent("email", isSignUp = true)
        assertEquals(FirebaseAnalytics.Event.LOGIN, captured[0].first)
        assertEquals("google", captured[0].second[FirebaseAnalytics.Param.METHOD])
        assertEquals(FirebaseAnalytics.Event.SIGN_UP, captured[1].first)
        assertEquals("email", captured[1].second[FirebaseAnalytics.Param.METHOD])
    }

    @Test
    fun `logLeaderboardSubmit clamps negative values and records outcome`() {
        AnalyticsHelper.logLeaderboardSubmit("mslug", -50L, -1, "accepted")
        val (name, params) = captured.first()
        assertEquals("leaderboard_score_submit", name)
        assertEquals("mslug", params["rom_slug"])
        assertEquals(0L, params[FirebaseAnalytics.Param.SCORE])
        assertEquals(0L, params["credits_used"])
        assertEquals("accepted", params["outcome"])
    }

    @Test
    fun `logCatalogSearch and logShare emit expected GA4 params`() {
        AnalyticsHelper.logCatalogSearch("metal slug", 3)
        AnalyticsHelper.logShare("replay", "rep-123", "android_sharesheet")
        assertEquals("catalog_search", captured[0].first)
        assertEquals("metal slug", captured[0].second["search_term"])
        assertEquals(3L, captured[0].second["results_count"])
        assertEquals(FirebaseAnalytics.Event.SHARE, captured[1].first)
        assertEquals("replay", captured[1].second[FirebaseAnalytics.Param.CONTENT_TYPE])
        assertEquals("rep-123", captured[1].second[FirebaseAnalytics.Param.ITEM_ID])
        assertTrue(captured.size == 2)
    }
}
