package com.yiyu.ledger

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yiyu.ledger.security.CloudKeyStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class KeyStoreTest {
    @Test fun secretIsEncryptedAndCanBeReadAfterRecreatingStore() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "key-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object: ContextWrapper(base) { override fun getNoBackupFilesDir() = directory }
        val alias = "yiyu.test.${UUID.randomUUID()}"
        val store = CloudKeyStore(context, alias)
        val placeholder = "sk-synthetic-unit-test-placeholder"
        try {
            store.save(placeholder)
            assertTrue(store.hasKey())
            assertFalse(File(directory, "cloud-key.enc").readText().contains(placeholder))
            assertEquals(placeholder, CloudKeyStore(context, alias).read())
            store.clear(); assertFalse(store.hasKey()); assertNull(store.read())
        } finally { store.clear(); directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }
}
