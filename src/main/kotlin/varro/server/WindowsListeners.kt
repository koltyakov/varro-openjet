package varro.server

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/** OWNER_PID_LISTENER filters in the kernel, without localized or unreliable state text. */
internal object WindowsListeners {
    fun read(port: Int): Set<Long> = read(port) { table, size, family ->
        NativeLibrary.getInstance("iphlpapi").getFunction("GetExtendedTcpTable")
            .invokeInt(arrayOf(table, size, false, family, 3, 0))
    }

    internal fun read(port: Int, query: (Pointer?, IntByReference, Int) -> Int): Set<Long> {
        require(port in 1..65535) { "Invalid Windows listener port" }
        val owners = linkedSetOf<Long>()
        for (family in listOf(2, 23)) {
            val size = IntByReference()
            check(query(null, size, family) in setOf(0, 122)) { "Windows listener table sizing failed" }
            var complete = false
            repeat(3) {
                if (complete) return@repeat
                val allocated = size.value
                check(allocated in 4..16 * 1024 * 1024) { "Invalid Windows listener table size" }
                Memory(allocated.toLong()).use { table ->
                    val code = query(table, size, family)
                    if (code == 122) return@use
                    check(code == 0) { "Windows listener table read failed: $code" }
                    val rowSize = if (family == 2) 24 else 56
                    val count = table.getInt(0)
                    check(size.value in 4..allocated && count >= 0 && count <= (size.value - 4) / rowSize) {
                        "Invalid Windows listener table length"
                    }
                    repeat(count) { index ->
                        val row = 4L + index.toLong() * rowSize
                        val offset = row + if (family == 2) 8 else 20
                        val localPort = ((table.getByte(offset).toInt() and 255) shl 8) or
                            (table.getByte(offset + 1).toInt() and 255)
                        if (localPort == port) {
                            val pid = table.getInt(row + if (family == 2) 20 else 52)
                            check(pid > 0) { "Invalid Windows listener owner" }
                            owners.add(pid.toLong())
                        }
                    }
                    complete = true
                }
            }
            check(complete) { "Windows listener table kept changing" }
        }
        return owners
    }
}
