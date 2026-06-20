package com.example.logger

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar

/**
 * Editor de program de inregistrare pt senzori ficsi (multi-fereastra + duty-cycle).
 * Scrie/citeste prin [Schedule]. Gol = programul implicit ([RecordWindow], noapte intreaga).
 * UI programatic (acelasi stil ca DiagnosticActivity). Nu atinge inregistrarea direct.
 */
class ScheduleEditorActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private val wins = ArrayList<Schedule.Win>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(28, 36, 28, 28)
            setBackgroundColor(0xFF121212.toInt())
        }
        setContentView(ScrollView(this).apply { addView(root) })
        Schedule.load(this)?.let { wins.addAll(it) }
        rebuild()
    }

    private fun rebuild() {
        root.removeAllViews()
        root.addView(h1("⏰ Program de înregistrare"))
        root.addView(hint("Senzor fix: când să înregistreze. Gol = IMPLICIT (noapte: apus−30 → răsărit+30). " +
            "Pentru ZIUA: presetul „☀ Zi\" (un tap) sau o fereastră cu „Oră fixă\". Ferestrele relative la " +
            "apus/răsărit se recalculează zilnic; + duty-cycle pentru autonomie."))

        // status orientativ (fara GPS: apus≈19:00, rasarit≈07:00)
        val now = Schedule.recordingNowOrNull(this, Calendar.getInstance(), null, null)
            ?: RecordWindow.isActiveNow(null, null)
        root.addView(small(if (now) "▶ Acum: ar înregistra (orientativ, fără GPS)"
                           else "⏸ Acum: pauză (orientativ, fără GPS)"))

        root.addView(h2("Ferestre"))
        if (wins.isEmpty()) {
            root.addView(small("— niciuna — se folosește programul IMPLICIT (noapte întreagă)."))
        } else {
            wins.forEachIndexed { i, w ->
                val rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                rowL.addView(small("• " + Schedule.describe(w)).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                rowL.addView(Button(this).apply {
                    text = "✕"; setOnClickListener { wins.removeAt(i); persist(); rebuild() }
                })
                root.addView(rowL)
            }
        }
        root.addView(Button(this).apply { text = "➕ Adaugă fereastră"; setOnClickListener { addWindowDialog() } })

        root.addView(h2("Presetări (înlocuiesc tot)"))
        presetBtn("🦇 Noapte întreagă (apus−30 → răsărit+30)", "noapte")
        presetBtn("🌅 Cor de zori + seară", "zori_seara")
        presetBtn("🔋 Noapte cu economie (5 rec / 30 pauză)", "noapte_eco")
        presetBtn("☀ Zi (răsărit → apus)", "zi")
        presetBtn("⏱ Non-stop (24h)", "nonstop")

        root.addView(h2(" "))
        root.addView(Button(this).apply {
            text = "↩ Revino la programul implicit"
            setOnClickListener {
                wins.clear(); Schedule.clear(this@ScheduleEditorActivity); rebuild()
                toast("Program implicit (noapte)")
            }
        })
        root.addView(Button(this).apply { text = "← Înapoi"; setOnClickListener { finish() } })
    }

    private fun presetBtn(label: String, key: String) {
        root.addView(Button(this).apply {
            text = label
            setOnClickListener {
                wins.clear(); wins.addAll(Schedule.preset(key)); persist(); rebuild(); toast("Setat")
            }
        })
    }

    private fun addWindowDialog() {
        val refs = arrayOf("Apus", "Răsărit", "Oră fixă")
        val refKeys = arrayOf("sunset", "sunrise", "abs")
        fun spinner(sel: Int) = Spinner(this).apply {
            adapter = ArrayAdapter(this@ScheduleEditorActivity, android.R.layout.simple_spinner_item, refs)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(sel)
        }
        fun num(hintTxt: String, def: String) = EditText(this).apply {
            hint = hintTxt; setText(def)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val spStart = spinner(0); val offStart = num("offset start (min, ex. -30)", "-30")
        val spEnd = spinner(1);   val offEnd = num("offset final (min, ex. 30)", "30")
        val dOn = num("rec min (0 = continuu)", "0"); val dOff = num("pauză min", "0")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0)
            addView(small("Start:")); addView(spStart); addView(offStart)
            addView(small("Final:")); addView(spEnd); addView(offEnd)
            addView(small("„Oră fixă\": offset = minute după miezul nopții (08:00 = 480, 20:00 = 1200)."))
            addView(small("Duty-cycle (lasă 0/0 = continuu):")); addView(dOn); addView(dOff)
        }
        AlertDialog.Builder(this).setTitle("Adaugă fereastră").setView(box)
            .setPositiveButton("Adaugă") { _, _ ->
                fun v(e: EditText) = e.text.toString().toIntOrNull() ?: 0
                wins.add(Schedule.Win(
                    refKeys[spStart.selectedItemPosition], v(offStart),
                    refKeys[spEnd.selectedItemPosition], v(offEnd), v(dOn), v(dOff)))
                persist(); rebuild()
            }
            .setNegativeButton("Renunță", null).show()
    }

    private fun persist() = Schedule.save(this, wins)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun h1(s: String) = TextView(this).apply { text = s; textSize = 20f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, 0, 0, 6) }
    private fun h2(s: String) = TextView(this).apply { text = s; textSize = 16f; setTextColor(0xFF80CBC4.toInt()); setPadding(0, 22, 0, 8) }
    private fun hint(s: String) = TextView(this).apply { text = s; textSize = 12f; setTextColor(0xFF9E9E9E.toInt()); setPadding(0, 0, 0, 6) }
    private fun small(s: String) = TextView(this).apply { text = s; textSize = 13f; setTextColor(0xFFE0E0E0.toInt()); setPadding(0, 4, 0, 4) }
}
