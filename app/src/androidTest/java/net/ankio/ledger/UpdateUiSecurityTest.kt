package net.ankio.ledger

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
            val instrument=InstrumentationRegistry.getInstrumentation()
            fun checkVisible()=instrument.uiAutomation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText("检查更新")?.any { it.text?.toString()=="检查更新" }==true
            fun awaitCheck(visible:Boolean){
                val end=SystemClock.uptimeMillis()+5000
                while(checkVisible()!=visible && SystemClock.uptimeMillis()<end)SystemClock.sleep(50)
                assertEquals("Update sheet visibility",visible,checkVisible())
            }
            awaitCheck(true)
            var x=0f;var y=0f
            scenario.onActivity { activity ->
                val location=IntArray(2);activity.webView.getLocationOnScreen(location)
                x=location[0]+activity.webView.width/2f;y=location[1]+16*activity.resources.displayMetrics.density
            }
            val down=SystemClock.uptimeMillis()
            for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){
                val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
                try{instrument.sendPointerSync(event)}finally{event.recycle()}
            }
            awaitCheck(false)
            web.run("document.querySelector('#app-updates').click()")
            awaitCheck(true)
            instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitCheck(false)
            web.await("page==='settings'")
        }
    }
}
