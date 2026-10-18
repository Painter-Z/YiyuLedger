package com.yiyu.ledger

import android.app.Application
import com.yiyu.ledger.data.LedgerDatabase
import com.yiyu.ledger.data.LedgerRepository
import com.yiyu.ledger.security.CloudKeyStore
import java.io.File

class LedgerApplication: Application() {
    val audioDirectory by lazy { File(filesDir, "voice-pending").apply { mkdirs() } }
    val repository by lazy { LedgerRepository(LedgerDatabase.open(this), audioDirectory) }
    val keyStore by lazy { CloudKeyStore(this) }
    val cloudPrompt by lazy { assets.open("cloud-prompt.txt").bufferedReader(Charsets.UTF_8).use { it.readText() } }
}
