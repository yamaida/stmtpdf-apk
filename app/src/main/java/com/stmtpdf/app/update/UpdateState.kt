package com.stmtpdf.app.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

enum class UpdatePhase { IDLE, DOWNLOADING, VERIFYING, READY, FAILED }

data class UpdateProgress(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val percent: Int = 0,
    val received: Long = 0,
    val total: Long = 0,
    val apkPath: String = "",
    val error: String = ""
)

/** 下载服务的进度出口：UI 订阅，服务写入。 */
object UpdateState {

    private val _flow = MutableStateFlow(UpdateProgress())
    val flow: StateFlow<UpdateProgress> = _flow

    fun set(value: UpdateProgress) {
        _flow.value = value
    }

    fun reset() {
        _flow.value = UpdateProgress()
    }
}

/** 失败文案统一英文（按交互方案：更新界面固定英文）。 */
object UpdateErrorText {

    fun of(message: String?, sizeBytes: Long): String = when {
        message.isNullOrBlank() -> "Download failed. Please retry."
        message == UpdateChecker.ERR_NO_SPACE -> "Not enough space. ${mb(sizeBytes)} required."
        message.startsWith("HTTP", ignoreCase = true) -> "Download server unavailable."
        message.contains("SHA", ignoreCase = true) ||
            message.contains("empty package", ignoreCase = true) -> "Package verification failed."
        else -> "Download paused. Check your connection."
    }

    fun mb(bytes: Long): String = if (bytes > 0) {
        String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    } else {
        ""
    }
}
