package com.example.callscheduler.data.parser

import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class CallMetadata(
    val filePath: String,
    val contactName: String,
    val phoneNumber: String,
    val callDateTime: LocalDateTime
)

object CallFilenameParser {
    // "/storage/emulated/0/Recordings/Call/" 내 "통화 [통화대상] [전화번호]_[날짜]_[시간].m4a" 등 파싱
    private val CALL_FILE_REGEX = Regex(
        pattern = """^(?:통화(?:\s*녹음)?)[_\s-]+(.+?)[_\s-]+(01[016789][-_]?\d{3,4}[-_]?\d{4})(?:[_\s-]+(\d{6}|\d{8})[_\s-]?(\d{6}))?\.(\w+)$"""
    )

    fun parse(file: File): CallMetadata {
        val match = CALL_FILE_REGEX.find(file.name)
        if (match != null) {
            val (rawName, rawPhone, datePart, timePart) = match.destructured
            val parsedDateTime = parseDateTime(datePart, timePart, file.lastModified())
            return CallMetadata(
                filePath = file.absolutePath,
                contactName = rawName.trim(),
                phoneNumber = rawPhone.replace("_", "-"),
                callDateTime = parsedDateTime
            )
        }

        return CallMetadata(
            filePath = file.absolutePath,
            contactName = file.nameWithoutExtension.removePrefix("통화 녹음").removePrefix("통화").trim().ifEmpty { "알 수 없는 상대" },
            phoneNumber = "",
            callDateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()),
                ZoneId.systemDefault()
            )
        )
    }

    private fun parseDateTime(datePart: String, timePart: String, fallbackMillis: Long): LocalDateTime {
        return try {
            when (datePart.length) {
                6 -> LocalDateTime.parse("$datePart$timePart", DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                8 -> LocalDateTime.parse("$datePart$timePart", DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                else -> throw IllegalArgumentException("Unsupported date format")
            }
        } catch (e: Exception) {
            LocalDateTime.ofInstant(
                Instant.ofEpochMilli(fallbackMillis.takeIf { it > 0 } ?: System.currentTimeMillis()),
                ZoneId.systemDefault()
            )
        }
    }
}
