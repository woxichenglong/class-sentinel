package com.classsentinel.docs

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSoakChecklistTest {

    @Test
    fun `soak checklist contains every required device scenario and measurement`() {
        val checklist = readRepositoryFile("docs/android-soak-checklist.md")
        val required = listOf(
            "连续监听 60–90 分钟",
            "屏幕亮/灭",
            "锁屏",
            "App 前台/后台",
            "Quick Settings Tile",
            "Wi-Fi/移动网络断开再恢复",
            "LLM 失败/恢复",
            "通知权限撤销",
            "麦克风权限中途撤销",
            "电池优化",
            "来电/音频焦点变化",
            "模型切换后下一 session",
            "STOP→START 多轮",
            "进程被系统杀死后的恢复",
            "PSS/RSS/native heap",
            "CPU/温度/电量",
            "transcript 数量与实际 Final 对账",
            "adb devices -l",
            "dumpsys meminfo",
            "dumpsys cpuinfo",
            "dumpsys batterystats",
            "不要用 JVM test 代替真机",
            "不要使用 adb input",
        )

        required.forEach { phrase ->
            assertTrue("checklist is missing: $phrase", checklist.contains(phrase))
        }
    }

    @Test
    fun `soak checklist requires bounded evidence and explicit pass criteria`() {
        val checklist = readRepositoryFile("docs/android-soak-checklist.md")

        assertTrue(checklist.contains("开始时间"))
        assertTrue(checklist.contains("结束时间"))
        assertTrue(checklist.contains("通过条件"))
        assertTrue(checklist.contains("失败条件"))
        assertTrue(checklist.contains("保存安全日志摘要"))
        assertTrue(checklist.contains("删除临时数据库副本"))
        assertTrue(checklist.contains("NOT RUN"))
    }

    private fun readRepositoryFile(relativePath: String): String {
        var directory: File? = File(System.getProperty("user.dir") ?: ".")
        while (directory != null) {
            val file = File(directory, relativePath)
            if (file.isFile) return file.readText()
            directory = directory.parentFile
        }
        error("Repository file not found: $relativePath")
    }
}
