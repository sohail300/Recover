package com.bytestudio.deletedsaver

import android.app.Application
import com.bytestudio.deletedsaver.capture.Alerts
import com.bytestudio.deletedsaver.data.Db
import com.bytestudio.deletedsaver.data.Prefs

class App : Application() {
    lateinit var db: Db
        private set
    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        db = Db(this)
        prefs = Prefs(this)
        Alerts.createChannel(this)
    }
}
