package net.ankio.ledger

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.ankio.ledger.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateUiSecurityTest {
    @Test fun updateEntryDoesNotEnableLedgerNetworkAndProviderIsPrivate(){
        val app=ApplicationProvider.getApplicationContext<Context>()
        val provider=app.packageManager.resolveContentProvider("${app.packageName}.updates",PackageManager.GET_META_DATA)!!
        assertFalse(provider.exported);assertTrue(provider.grantUriPermissions)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web=WebUiProbe(scenario);web.ready();web.run("go('settings')")
            web.await("!!document.querySelector('#app-updates')")
            scenario.onActivity { activity ->
                assertTrue(activity.webView.settings.blockNetworkLoads)
                assertFalse(activity.webView.settings.allowFileAccess)
                assertFalse(activity.webView.settings.allowContentAccess)
            }
            // Opening the entry only discloses networking; checking needs a second action.
            web.run("document.querySelector('#app-updates').click()")
            scenario.onActivity { activity -> assertTrue(activity.webView.settings.blockNetworkLoads) }
        }
    }
}
