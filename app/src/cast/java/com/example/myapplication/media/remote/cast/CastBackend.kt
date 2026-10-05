package com.example.myapplication.media.remote.cast

import android.content.Context
import com.example.myapplication.media.remote.RemotePlaybackAdapter

/** Сборка с Google Cast (GitHub-релизы). F-Droid удаляет папку src/cast — там берётся src/nocast. */
fun createCastAdapter(context: Context): RemotePlaybackAdapter? =
    GoogleCastAdapter(context).takeIf { it.available }
