package dev.klone

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetaspaceAdvisorTest {

    private val mb = 1024L * 1024L

    @Test
    fun `measured usage warns near the limit with a rounded recommendation`() {
        val message = MetaspaceAdvisor.measured(470 * mb, 512 * mb, warnPercent = 80)
        assertNotNull(message)
        assertTrue(message!!.contains("470/512 MB (91%)"))
        assertTrue(message.contains("-XX:MaxMetaspaceSize=768m"), message) // 470 * 1.5 = 705 → 768
    }

    @Test
    fun `measured usage below the threshold or without a limit is fine`() {
        assertNull(MetaspaceAdvisor.measured(300 * mb, 512 * mb, warnPercent = 80))
        assertNull(MetaspaceAdvisor.measured(900 * mb, -1, warnPercent = 80))
    }

    @Test
    fun `estimate warns when the cloned repos will not fit`() {
        // 160 + 9 * 60 = 700 MB > 512 MB (pokedex-compose with 9 repos hung at 512m)
        val message = MetaspaceAdvisor.estimated(9, 512 * mb)
        assertNotNull(message)
        assertTrue(message!!.contains("about 700 MB"))
        assertTrue(message.contains("-XX:MaxMetaspaceSize=1280m"), message) // 700 * 1.5 = 1050 → 1280

        assertNull(MetaspaceAdvisor.estimated(2, 512 * mb))
        assertNull(MetaspaceAdvisor.estimated(30, -1))
    }
}
