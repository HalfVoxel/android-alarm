package com.arongranberg.alarm

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import com.arongranberg.alarm.R.*
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.util.Log

import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley

import org.json.JSONException
import org.json.JSONObject

import android.os.Handler
import android.support.v4.graphics.ColorUtils
import android.view.View
import android.view.animation.AnimationSet
import android.widget.*
import com.android.volley.*
import org.joda.time.*
import org.joda.time.format.ISODateTimeFormat
import java.util.*
import kotlin.concurrent.timerTask

class MainActivity : AppCompatActivity() {
    val handler = Handler()
    var timer : Timer = Timer()
    var label: TextView? = null
    var startButton: Button? = null
    var progress: ProgressBar? = null
    var picker : TimePicker? = null

    var dirtyingTime : DateTime = DateTime.now()
    internal var lastSyncFailed = Observable(false)
    internal var lastSyncedVersion = -1
    internal var dirtyVersion = 0
    internal var hasPerformedGetSync = Observable(false)

    var alarmEnabled = Observable(false)
    val synced = Observable(SyncState.NotSyncing)

    companion object {
        private val TAG = "Alarm"
        internal var lastSyncTime = DateTime.now()
    }

    enum class SyncState {
        NotSyncing,
        Syncing
    }

    interface IObservable {
        fun listen (listener : (() -> Unit))
    }

    class Observable<T>(initial : T) : IObservable {
        private var mValue = initial
        private val listeners = ArrayList<((T, T) -> Unit)>()

        public var value : T
            get() = mValue
            set(value) {
                if (value != mValue) {
                    val prev = mValue
                    mValue = value
                    onChanged(prev, value)
                }
            }

        public override fun listen (listener : (() -> Unit)) {
            listen { a, b -> listener() }
        }

        public fun listen (listener : ((T,T) -> Unit)) {
            listeners.add(listener)
        }

        private fun onChanged(prev : T, current : T) {
            for (listener in listeners) {
                listener(prev, current)
            }
        }

        public fun init() {
            onChanged(value, value)
        }
    }

    fun <T>react (listener : ((T,T) -> Unit), observable : Observable<T>) {
        observable.listen(listener)
    }

    fun react(listener : (() -> Unit), vararg observables : IObservable) {
        for(observable in observables) {
            observable.listen(listener)
        }
    }

    class AlarmTime(val hour : Int, val minute : Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout.activity_main)

        picker = findViewById(id.time_picker) as TimePicker
        picker!!.setIs24HourView(true)

        label = findViewById(id.label) as TextView;
        progress = findViewById(id.progressBar) as ProgressBar;

        startButton = findViewById(id.start) as Button
        startButton!!.setOnClickListener({ alarmEnabled.value = !alarmEnabled.value })

        picker!!.setOnTimeChangedListener { timePicker, i, i1 -> refreshLabel(); dirty() }
        react({ refreshLabel() }, alarmEnabled, lastSyncFailed)

        val alarmTimeLabel = findViewById(id.alarmTimeLabel)

        var currentAnimationSet = AnimatorSet()
        react({ previous, current ->
            handler.post {
                if (currentAnimationSet != null) currentAnimationSet.cancel()
                currentAnimationSet = AnimatorSet()

                startButton!!.text = if (current) "Stop Alarm" else "Start Alarm"

                val enabledColor = resources.getColor(color.alarmEnabledButtonColor, theme)
                val disabledColor = resources.getColor(color.alarmDisabledButtonColor, theme)
                val startColor = if (previous) enabledColor else disabledColor
                val endColor = if (current) enabledColor else disabledColor

                val fadeIn = if(current) alarmTimeLabel else picker!!
                val fadeOut = if(current) picker!! else alarmTimeLabel

                val anim1 = ObjectAnimator.ofFloat(fadeOut, "alpha", fadeOut.alpha, 0f).setDuration(300)
                val anim2 = ObjectAnimator.ofFloat(fadeIn, "alpha", fadeIn.alpha, 1f).setDuration(300)
                val anim3 = ObjectAnimator.ofArgb(startButton!!.background, "tint", startColor, endColor).setDuration(400)

                currentAnimationSet.playSequentially(anim1, anim2)
                currentAnimationSet.play(anim3)
                currentAnimationSet.start()
            }
        }, alarmEnabled)

        react({ previous, current ->
            val targetAlpha = if (current == SyncState.Syncing) 1.0f else 0.0f
            progress!!.animate().alpha(targetAlpha)
        }, synced)

        //react({ prev, current -> picker!!.isEnabled = current }, hasPerformedGetSync)

        react({ dirty(); sync() }, alarmEnabled)

        Log.v(TAG, "CREATE")
    }

    fun refreshLabel () {
        if (lastSyncFailed.value) {
            label!!.text = "Cannot connect to alarm!"
        } else {
            val until = Period(DateTime.now(), getWakeupDateTime())
            var untilText = ""
            if (until.hours > 1) {
                untilText = until.hours.toString() + " hours and "
            } else if (until.hours == 1) {
                untilText = until.hours.toString() + " hour and "
            }

            untilText += until.minutes.toString() + " minutes"

            label!!.text = "Waking up in " + untilText
        }
    }

    override fun onResume() {
        super.onResume()
        Log.v(TAG, "RESUME")
        hasPerformedGetSync.value = false
        synced.value = SyncState.NotSyncing

        lastSyncFailed.init()
        hasPerformedGetSync.init()
        alarmEnabled.init()
        synced.init()

        timer.cancel()
        timer = Timer()
        timer.schedule(timerTask { handler.post({ refresh() }) }, 0L, 500L)

        refresh()
    }

    override fun onStop() {
        super.onPause()
        timer.cancel()
    }

    internal fun refresh() {
        refreshLabel()

        val alarmTimeLabel = findViewById(id.alarmTimeLabel) as TextView
        alarmTimeLabel.text = DateTime.now().toString("HH:mm")

        if ((lastSyncedVersion < dirtyVersion || !hasPerformedGetSync.value) && synced.value != SyncState.Syncing && Duration(dirtyingTime, DateTime.now()).millis > 800) {
            sync()
        }
    }

    internal fun getWakeupDateTime(): DateTime {
        val wakeup = getWakeupTime()
        val time = DateTime.now().withHourOfDay(wakeup.hour).withMinuteOfHour(wakeup.minute)
        if (time < DateTime.now()) {
            return time.plusDays(1)
        } else {
            return time
        }
    }

    internal fun getWakeupTime(): AlarmTime = AlarmTime(picker!!.hour, picker!!.minute)

    internal fun setWakeupTime(time : DateTime) {
        picker!!.hour = time.hourOfDay
        picker!!.minute = time.minuteOfHour
    }

    internal fun stopAlarm() {
        //if (alarmEnabled) {
         //   alarmEnabled = false
          //  dirty()
        //}
    }

    internal fun dirty(syncImmediately : Boolean = false) {
        Log.v(TAG, "Dirtying...")
        dirtyVersion++
        if (syncImmediately) {
            dirtyingTime = DateTime.now().minusDays(1000)
        } else {
            dirtyingTime = DateTime.now()
        }
        refresh()
    }

    fun sync() {
        if (synced.value != SyncState.Syncing) {
            sync(hasPerformedGetSync.value)
        }
    }

    internal fun sync(upload : Boolean) {
        val queue = Volley.newRequestQueue(this)
        val url = "http://home.arongranberg.com:6000/" + (if (upload) "store" else "get")

        val jsonRequest = JSONObject()

        try {
            if (upload) {
                val fmt = ISODateTimeFormat.dateHourMinuteSecondFraction()
                val time = getWakeupDateTime().toDateTime(DateTimeZone.UTC)
                jsonRequest.put("time", fmt.print(time))
                jsonRequest.put("enabled", alarmEnabled.value)
            }
            jsonRequest.put("secret", 796134889)
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to serialize")
            return
        }

        // Will be reset if the sync fails
        synced.value = SyncState.Syncing
        val version = dirtyVersion
        Log.v(TAG, "Starting " + version + " " + url)
        // Request a string response from the provided URL.
        val request = JsonObjectRequest(Request.Method.POST, url, jsonRequest,
                Response.Listener<org.json.JSONObject> {
                    response ->
                        Log.v(TAG, "Response")
                        if (upload) {
                            lastSyncedVersion = version
                        } else {
                            hasPerformedGetSync.value = true
                            // User has not changed UI since sync was started. Ok to apply settings
                            Log.v(TAG,response.toString())
                            alarmEnabled.value = response.getBoolean("enabled")
                            Log.v(TAG, "Response: " + response.getBoolean("enabled"))
                            val parser = ISODateTimeFormat.dateTimeParser().withZoneUTC()
                            setWakeupTime(parser.parseDateTime(response.getString("time")).withZone(DateTimeZone.getDefault()))
                            //response.getInt("hour"), response.getInt("minute")))

                            lastSyncedVersion = dirtyVersion
                            Log.v(TAG, "Response Complete at " + lastSyncedVersion)
                        }

                        synced.value= SyncState.NotSyncing
                        lastSyncFailed.value = false
                        handler.post({ refresh() })
                },
                Response.ErrorListener {
                    error ->
                        Log.v(TAG, "Error " + error.message)
                        synced.value = SyncState.NotSyncing
                        lastSyncFailed.value = true
                })

        // Add the request to the RequestQueue.
        queue.add(request)
    }

    /*Period oldest = new Period(lastSyncTime, DateTime.now());
    if (oldest.getSeconds() > 200) {
        startSync(null, null);
    }*/
}
