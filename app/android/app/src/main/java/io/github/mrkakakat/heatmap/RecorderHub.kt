package io.github.mrkakakat.heatmap

/** Process-wide link between the UI (activity, Capacitor plugin) and the running recorder service. */
object RecorderHub {
    @Volatile var recorder: Recorder? = null
        set(value) {
            field = value
            value?.setActive(active)
        }

    /** True while the app is on screen. Remembered so a recorder started later picks it up. */
    @Volatile var active = false
        private set

    fun setActive(value: Boolean) {
        active = value
        recorder?.setActive(value)
    }
}
