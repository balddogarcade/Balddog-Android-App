package dog.bald.arcade

import android.app.Application
import com.google.firebase.crashlytics.FirebaseCrashlytics

class BalddogApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
            crashlytics.setCustomKey("abi", "arm64-v8a")
            crashlytics.setCustomKey("version_name", BuildConfig.VERSION_NAME)
            crashlytics.setCustomKey("version_code", BuildConfig.VERSION_CODE)
        }
    }
}
