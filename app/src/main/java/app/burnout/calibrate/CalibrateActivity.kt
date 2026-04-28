package app.burnout.calibrate

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.burnout.R

class CalibrateActivity : AppCompatActivity() {

    private val state = CorrectionState()
    private lateinit var glView: GLSurfaceView
    private lateinit var statusLine: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibrate)
        enterImmersiveMode()

        glView = findViewById(R.id.gl_view)
        glView.setEGLContextClientVersion(3)
        glView.setRenderer(CorrectionRenderer(this, state))
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        statusLine = findViewById(R.id.status_line)
        statusLine.text = "Calibrate — Phase E scaffold"

        // Phase F wires Save/Export.
        findViewById<Button>(R.id.btn_save).setOnClickListener {
            Toast.makeText(this, "Save: wired in Phase F", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btn_export).setOnClickListener {
            Toast.makeText(this, "Export: wired in Phase F", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() { super.onResume(); glView.onResume() }
    override fun onPause()  { glView.onPause();  super.onPause()  }

    private fun enterImmersiveMode() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let { c ->
            c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            c.systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
