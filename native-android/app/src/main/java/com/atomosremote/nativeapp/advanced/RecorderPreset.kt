package com.atomosremote.nativeapp.advanced

data class RecorderPreset(
    val id: String,
    val name: String,
    val commandKeys: List<String>,
    val note: String = ""
)
