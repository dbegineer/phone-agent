package com.phoneagent.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.phoneagent.core.SafetyGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

/**
 * Phase 1 UI, built in code so the project needs no resource files.
 * The agent only runs when the user taps Send or speaks. It never starts anything on its own.
 */
class MainActivity : Activity() {
    private val scope = MainScope()
    private val gate = SafetyGate()
    private lateinit var agent: AgentEngine
    private lateinit var executor: ToolExecutor
    private lateinit var audit: Audit

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var stopBtn: Button

    private var taskJob: Job? = null
    @Volatile private var cancelled = false

    private val modelFile get() = File(filesDir, "model.litertlm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        agent = AgentEngine(applicationContext)
        executor = ToolExecutor(this, gate)
        audit = Audit(this)
        buildUi()
        if (modelFile.exists()) loadModel() else setStatus("Pick a .litertlm model file to begin.")
    }

    override fun onDestroy() {
        scope.cancel()
        agent.close()
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(dp(16), bars.top + dp(16), dp(16), bars.bottom + dp(16))
                insets
            }
        }
        root.addView(TextView(this).apply { text = "Phone Agent"; textSize = 22f })
        status = TextView(this).apply { textSize = 14f; setPadding(0, dp(4), 0, dp(8)) }
        root.addView(status)

        val topRow = LinearLayout(this)
        val modelBtn = Button(this).apply { text = "Pick model"; setOnClickListener { pickModel() } }
        stopBtn = Button(this).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#C62828"))
            isEnabled = false
            setOnClickListener { stopNow() }
        }
        topRow.addView(modelBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(stopBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(topRow)

        logView = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        input = EditText(this).apply {
            hint = "Ask me to do something"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
        }
        root.addView(input)

        val bottomRow = LinearLayout(this).apply { gravity = Gravity.END }
        val micBtn = Button(this).apply { text = "Speak"; setOnClickListener { startVoice() } }
        sendBtn = Button(this).apply { text = "Send"; setOnClickListener { submit() } }
        bottomRow.addView(micBtn)
        bottomRow.addView(sendBtn)
        root.addView(bottomRow)
        setContentView(root)
    }

    private fun setStatus(s: String) { runOnUiThread { status.text = s } }

    private fun addLog(line: String) {
        audit.write(line)
        runOnUiThread {
            val t = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            logView.append("$t  $line\n\n")
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    // ---------- model ----------

    private fun pickModel() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, REQ_MODEL)
    }

    private fun copyModel(uri: Uri) {
        setStatus("Copying model, this can take a minute...")
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val tmp = File(filesDir, "model.tmp")
                    contentResolver.openInputStream(uri)!!.use { src -> FileOutputStream(tmp).use { src.copyTo(it) } }
                    if (modelFile.exists()) modelFile.delete()
                    check(tmp.renameTo(modelFile)) { "rename failed" }
                }
                loadModel()
            } catch (t: Throwable) {
                setStatus("Could not copy the model: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun loadModel() {
        setStatus("Loading model...")
        scope.launch {
            try {
                withContext(Dispatchers.Default) { agent.load(modelFile.path) }
                setStatus("Ready. Everything runs on this phone. No internet permission.")
            } catch (t: Throwable) {
                setStatus("Model failed to load: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    // ---------- running a request ----------

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (!agent.isReady) { setStatus("Load a model first."); return }
        input.setText("")
        addLog("You: $text")
        cancelled = false
        sendBtn.isEnabled = false
        stopBtn.isEnabled = true
        taskJob = scope.launch {
            try {
                val reply = agent.runTask(
                    userText = text,
                    gate = gate,
                    confirm = { askUser(it) },
                    runTool = { call -> withContext(Dispatchers.Main) { executor.execute(call) } },
                    log = { addLog(it) },
                    isCancelled = { cancelled },
                )
                addLog("Agent: $reply")
            } catch (e: CancellationException) {
                addLog("Stopped by you.")
            } catch (t: Throwable) {
                addLog("Error: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                sendBtn.isEnabled = true
                stopBtn.isEnabled = false
            }
        }
    }

    /** Kill switch: stops the loop and ignores whatever the model is still computing. */
    private fun stopNow() {
        cancelled = true
        taskJob?.cancel()
    }

    private suspend fun askUser(summary: String): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Boolean> { cont ->
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Allow this action?")
                .setMessage(summary)
                .setCancelable(false)
                .setPositiveButton("Allow once") { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(false) }
                .create()
            cont.invokeOnCancellation { dialog.dismiss() }
            dialog.show()
        }
    }

    // ---------- voice ----------

    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        try {
            startActivityForResult(i, REQ_VOICE)
        } catch (e: Exception) {
            setStatus("No speech recognizer available. Type instead.")
        }
    }

    @Deprecated("Fine for this small app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_MODEL -> data.data?.let { copyModel(it) }
            REQ_VOICE -> {
                val heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (heard != null) { input.setText(heard); input.setSelection(heard.length) }
            }
        }
    }

    private companion object {
        const val REQ_MODEL = 1
        const val REQ_VOICE = 2
    }
}
