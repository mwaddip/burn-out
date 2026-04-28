package app.burnout

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import app.burnout.mark.MarkActivity

class LauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
        findViewById<Button>(R.id.btn_mark).setOnClickListener {
            startActivity(Intent(this, MarkActivity::class.java))
        }
        // CALIBRATE: enabled in Phase 4.
        findViewById<Button>(R.id.btn_calibrate).isEnabled = false
    }
}
