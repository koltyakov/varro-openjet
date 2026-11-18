package varro.server

import org.junit.Assert.*
import org.junit.Test

class WindowsListenersTest {
    @Test fun `native listener tables include both families regardless of state and never cache absence`() {
        var present = true
        val query = { table: com.sun.jna.Pointer?, size: com.sun.jna.ptr.IntByReference, family: Int ->
            val rowSize = if (family == 2) 24 else 56
            if (table == null) { size.value = 4 + rowSize; 122 } else {
                table.clear(size.value.toLong())
                table.setInt(0, if (present) 1 else 0)
                val port = 4L + if (family == 2) 8 else 20
                table.setByte(port, 16); table.setByte(port + 1, 0)
                table.setInt(4L + if (family == 2) 20 else 52, if (family == 2) 123 else 456)
                0
            }
        }
        assertEquals(setOf(123L, 456L), WindowsListeners.read(4096, query))
        present = false
        assertEquals(emptySet<Long>(), WindowsListeners.read(4096, query))
    }

    @Test fun `malformed failed or continually growing tables cannot authorize ownership`() {
        for (scenario in listOf("failure", "size", "count", "pid", "growing")) {
            assertThrows(IllegalStateException::class.java) {
                WindowsListeners.read(4096) { table, size, _ ->
                    when {
                        scenario == "failure" -> 5
                        table == null -> { size.value = if (scenario == "size") Int.MAX_VALUE else 60; 122 }
                        scenario == "growing" -> 122
                        else -> {
                            table.clear(size.value.toLong())
                            table.setInt(0, if (scenario == "count") 100 else 1)
                            table.setByte(12, 16)
                            0
                        }
                    }
                }
            }
        }
    }
}
