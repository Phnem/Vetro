package com.example.myapplication.audiobooks.domain.model

import java.util.UUID

@JvmInline
value class WorkId(val value: String) {
    init { require(runCatching { UUID.fromString(value) }.isSuccess) { "WorkId must be a UUID" } }
    companion object { fun new(): WorkId = WorkId(UUID.randomUUID().toString()) }
}

@JvmInline
value class NarrationId(val value: String) {
    init { require(runCatching { UUID.fromString(value) }.isSuccess) { "NarrationId must be a UUID" } }
    companion object { fun new(): NarrationId = NarrationId(UUID.randomUUID().toString()) }
}

