package relay.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import relay.memory.engine.InMemoryMemoryStore

fun testContext(): Context = ApplicationProvider.getApplicationContext()

fun testStore() = InMemoryMemoryStore(testContext())
