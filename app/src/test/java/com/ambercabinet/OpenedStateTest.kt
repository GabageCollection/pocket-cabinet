package com.ambercabinet

import com.ambercabinet.core.model.OpenedState
import com.ambercabinet.core.model.toOpenedAt
import org.junit.Assert.*
import org.junit.Test

/** 开瓶状态 → openedAt：三态各自落成什么，以及「选了其他日期但没挑那天」不能凭空写成开瓶时间 */
class OpenedStateTest {

    @Test fun unopenedWritesNoOpenedAt() {
        assertNull(OpenedState.UNOPENED.toOpenedAt(date = 1000L, now = 2000L))
    }

    @Test fun todayUsesNow() {
        assertEquals(2000L, OpenedState.TODAY.toOpenedAt(date = 1000L, now = 2000L))
    }

    @Test fun customUsesPickedDay() {
        /* 其他日期以用户挑的那天为准，不被 now 覆盖 */
        assertEquals(1000L, OpenedState.CUSTOM.toOpenedAt(date = 1000L, now = 2000L))
    }

    @Test fun customWithoutDateFallsBackToUnopened() {
        assertNull(OpenedState.CUSTOM.toOpenedAt(date = null, now = 2000L))
    }
}
