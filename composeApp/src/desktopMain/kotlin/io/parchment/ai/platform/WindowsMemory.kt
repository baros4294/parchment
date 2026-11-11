package io.parchment.ai.platform

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure

/** Total physical memory from Windows; the bundled runtime leaves out java.management, so this asks kernel32. */
internal object WindowsMemory {
    @Suppress("unused") // Fields are filled by Windows through JNA.
    @Structure.FieldOrder(
        "dwLength", "dwMemoryLoad", "ullTotalPhys", "ullAvailPhys", "ullTotalPageFile",
        "ullAvailPageFile", "ullTotalVirtual", "ullAvailVirtual", "ullAvailExtendedVirtual"
    )
    class MemoryStatusEx : Structure() {
        @JvmField var dwLength: Int = 0
        @JvmField var dwMemoryLoad: Int = 0
        @JvmField var ullTotalPhys: Long = 0
        @JvmField var ullAvailPhys: Long = 0
        @JvmField var ullTotalPageFile: Long = 0
        @JvmField var ullAvailPageFile: Long = 0
        @JvmField var ullTotalVirtual: Long = 0
        @JvmField var ullAvailVirtual: Long = 0
        @JvmField var ullAvailExtendedVirtual: Long = 0
    }

    private interface Kernel32 : Library {
        fun GlobalMemoryStatusEx(status: MemoryStatusEx): Boolean
    }

    /** 0 when unknown (not Windows, or the call failed), which callers treat as "not low". */
    fun totalPhysicalBytes(): Long = runCatching {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return 0L
        val status = MemoryStatusEx()
        status.dwLength = status.size()
        if (Native.load("kernel32", Kernel32::class.java).GlobalMemoryStatusEx(status)) status.ullTotalPhys else 0L
    }.getOrDefault(0L)
}
