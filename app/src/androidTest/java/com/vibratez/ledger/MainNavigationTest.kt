package com.vibratez.ledger

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
class MainNavigationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun ledgerIsStartPageAndAllThreeDestinationsAreReachable() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("am start -W -n ${BuildConfig.APPLICATION_ID}/.MainActivity")
            .close()
        val monthTitle = "${YearMonth.now()} 月预算"
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            composeRule.onAllNodesWithText(monthTitle).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText(monthTitle).assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToIndex(5)
        composeRule.onNodeWithText("当日流水").assertIsDisplayed()

        composeRule.onNodeWithText("识别").performClick()
        composeRule.onNodeWithText("导入支付宝/微信账单").assertIsDisplayed()
        composeRule.onNodeWithText("最近截图").assertIsDisplayed()

        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("云端识别设置").assertIsDisplayed()
        composeRule.onNode(hasScrollAction()).performScrollToIndex(2)
        composeRule.onNodeWithText("应用版本 0.3.0").assertIsDisplayed()
    }
}
