package com.txlnk.sdk.core

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.time

@OptIn(ExperimentalForeignApi::class)
internal actual fun currentEpochMillis(): Long = time(null) * 1000L
