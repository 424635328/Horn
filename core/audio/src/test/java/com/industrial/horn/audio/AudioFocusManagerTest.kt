package com.industrial.horn.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 焦点收支平衡（S10 验收）：**每一次 ACK 过的 request 有且只有一次 abandon**。
 *
 * 记账逻辑被抽到 [FocusLedger]（不接触 Android 对象），因此这里可以对 1:1 做纯 JVM 断言；
 * [AudioFocusManager] 只负责把 Android 的 request/abandon 注入。历史缺陷是
 * 「系统收回焦点时只清引用不 abandon」，在 `dumpsys audio` 里表现为永不释放的焦点持有者。
 */
class AudioFocusManagerTest {
    private class Ledger(
        private val grant: Boolean,
    ) {
        val requests = AtomicInteger(0)
        val abandons = AtomicInteger(0)
        private val focusLedger = FocusLedger<String>()

        fun requestFocus(): Boolean =
            focusLedger.acquire(
                token = "focus-token",
                request = {
                    requests.incrementAndGet()
                    if (grant) 1 else 0
                },
                granted = 1,
            )

        fun abandonFocus(): Boolean =
            focusLedger.release { abandons.incrementAndGet() }

        val isHeld: Boolean
            get() = focusLedger.isHeld
    }

    @Test
    fun grantedRequestIsAbandonedExactlyOnce() {
        val ledger = Ledger(grant = true)
        assertTrue(ledger.requestFocus())
        assertEquals("一次成功请求", 1, ledger.requests.get())
        assertEquals("此时不应有 abandon", 0, ledger.abandons.get())

        assertTrue(ledger.abandonFocus())
        assertEquals("交还一次", 1, ledger.abandons.get())
        assertEquals(1, ledger.requests.get())
        assertFalse("交还后不再持有", ledger.isHeld)
    }

    @Test
    fun abandonedFocusIsNotDoubleReleased() {
        val ledger = Ledger(grant = true)
        ledger.requestFocus()
        assertTrue(ledger.abandonFocus())
        assertFalse("第二次交还必须是无操作", ledger.abandonFocus())
        assertEquals("request:abandon 必须是 1:1", 1, ledger.abandons.get())
    }

    @Test
    fun deniedRequestDoesNotEnterTheLedger() {
        val ledger = Ledger(grant = false)
        assertFalse(ledger.requestFocus())
        assertEquals(1, ledger.requests.get())
        assertFalse("失败请求没有入账", ledger.isHeld)
        assertFalse("未持有时交还必须是无操作", ledger.abandonFocus())
        assertEquals("失败请求不得产生 abandon", 0, ledger.abandons.get())
    }

    @Test
    fun repeatedRequestWhileHeldDoesNotRequestAgain() {
        val ledger = Ledger(grant = true)
        assertTrue(ledger.requestFocus())
        assertTrue(ledger.requestFocus())
        assertEquals("持有时重复请求必须幂等", 1, ledger.requests.get())
        ledger.abandonFocus()
        assertEquals(1, ledger.abandons.get())
    }

    @Test
    fun concurrentReleaseYieldsExactlyOneAbandon() {
        val ledger = Ledger(grant = true)
        ledger.requestFocus()

        val racers = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(racers)
        val winners = AtomicInteger(0)
        repeat(racers) {
            Thread {
                start.await()
                if (ledger.abandonFocus()) winners.incrementAndGet()
                done.countDown()
            }.start()
        }
        start.countDown()
        done.await()

        assertEquals("并发交还只能有一个成功者", 1, winners.get())
        assertEquals("abandon 次数必须仍是 1", 1, ledger.abandons.get())
        assertEquals(1, ledger.requests.get())
    }
}
