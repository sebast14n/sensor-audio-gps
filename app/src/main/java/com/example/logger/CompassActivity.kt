package com.example.logger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.*

class CompassActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var compassView: CompassView
    private lateinit var tvDistance: TextView
    private lateinit var tvBearing: TextView
    private lateinit var tvTarget: TextView

    private var targetLat = 0.0
    private var targetLon = 0.0
    private var targetName = ""
    private var currentLocation: Location? = null
    private var currentAzimuth = 0f

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var gravityValues: FloatArray? = null
    private var magneticValues: FloatArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compass)

        targetLat  = intent.getDoubleExtra("lat", 0.0)
        targetLon  = intent.getDoubleExtra("lon", 0.0)
        targetName = intent.getStringExtra("name") ?: "Destinație"

        compassView = findViewById(R.id.compassView)
        tvDistance  = findViewById(R.id.tvDistance)
        tvBearing   = findViewById(R.id.tvBearing)
        tvTarget    = findViewById(R.id.tvTarget)
        tvTarget.text = "📍 $targetName"

        // Buton: deschide aceeasi destinatie pe harta satelitara (osmdroid, cache offline)
        findViewById<android.widget.Button>(R.id.btnOpenMap).setOnClickListener {
            startActivity(android.content.Intent(this, MapActivity::class.java).apply {
                putExtra("lat", targetLat)
                putExtra("lon", targetLon)
                putExtra("name", targetName)
            })
        }

        sensorManager   = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 3000L, 1f,
                object : LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        currentLocation = loc
                        updateDisplay()
                    }
                    override fun onProviderDisabled(p: String) {}
                    override fun onProviderEnabled(p: String) {}
                }
            )
        } catch (_: SecurityException) {}
    }

    override fun onResume() {
        super.onResume()
        // Prefer TYPE_ROTATION_VECTOR — senzor virtual care fuzioneaza accel+mag+gyro,
        // mult mai stabil decat lucrul direct cu magnetometru raw.
        val rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotSensor != null) {
            sensorManager.registerListener(this, rotSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            // Fallback: accelerometru + magnetometru (telefoane vechi)
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                currentAzimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                if (currentAzimuth < 0) currentAzimuth += 360f
                updateDisplay()
                return
            }
            Sensor.TYPE_ACCELEROMETER  -> gravityValues  = event.values.clone()
            Sensor.TYPE_MAGNETIC_FIELD -> magneticValues = event.values.clone()
        }
        // Fallback path (accel + mag)
        val g = gravityValues ?: return
        val m = magneticValues ?: return
        if (SensorManager.getRotationMatrix(rotationMatrix, null, g, m)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            currentAzimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (currentAzimuth < 0) currentAzimuth += 360f
            updateDisplay()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateDisplay() {
        val loc = currentLocation
        if (loc == null) {
            tvDistance.text = "Se caută GPS..."
            tvBearing.text  = ""
            compassView.setAngles(0f, 0f)
            return
        }

        val target = Location("").apply { latitude = targetLat; longitude = targetLon }
        val distanceM   = loc.distanceTo(target)
        val bearingToTarget = loc.bearingTo(target)  // grade față de nord geografic

        val distText = if (distanceM < 1000) "%.0fm".format(distanceM)
                       else "%.2fkm".format(distanceM / 1000)

        val relBearing = ((bearingToTarget - currentAzimuth + 360) % 360)
        tvDistance.text = distText
        // Indicație direcțională în limbaj natural
        val hint = when {
            relBearing < 15 || relBearing > 345 -> "↑ DREPT ÎNAINTE"
            relBearing in 15.0..75.0   -> "↗ dreapta-față"
            relBearing in 75.0..105.0  -> "→ DREAPTA"
            relBearing in 105.0..165.0 -> "↘ dreapta-spate"
            relBearing in 165.0..195.0 -> "↓ ÎN SPATE"
            relBearing in 195.0..255.0 -> "↙ stânga-spate"
            relBearing in 255.0..285.0 -> "← STÂNGA"
            relBearing in 285.0..345.0 -> "↖ stânga-față"
            else -> ""
        }
        tvBearing.text = "$hint\nPrivești spre ${currentAzimuth.toInt()}° · Destinația la ${bearingToTarget.toInt()}° de Nord"
        compassView.setAngles(currentAzimuth, bearingToTarget)
    }
}

class CompassView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var azimuth = 0f    // orientarea telefonului față de nord
    private var bearing = 0f    // direcția destinației față de nord

    private val paintTarget = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66BB6A"); strokeWidth = 18f; strokeCap = Paint.Cap.ROUND
    }
    private val paintCircle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#333333"); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val paintN = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF5350"); textSize = 36f; textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun setAngles(az: Float, bear: Float) {
        azimuth = az; bearing = bear; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(cx, cy) * 0.85f

        canvas.drawCircle(cx, cy, r, paintCircle)

        // O singura sageata: spre destinatie (verde, mare, vizibila)
        val targetAngle = bearing - azimuth
        drawArrow(canvas, cx, cy, r * 0.9f, targetAngle, paintTarget)

        // Litera "N" mica, in afara cercului, la pozitia nordului real
        // Util pentru a verifica ca senzorul de orientare functioneaza.
        val a = Math.toRadians((-azimuth).toDouble())
        val px = cx + (r + 35) * sin(a).toFloat()
        val py = cy - (r + 35) * cos(a).toFloat() + 12
        canvas.drawText("N", px, py, paintN)
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, len: Float, angleDeg: Float, paint: Paint) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val ex = cx + len * sin(rad).toFloat()
        val ey = cy - len * cos(rad).toFloat()
        canvas.drawLine(cx, cy, ex, ey, paint)
        // vârf săgeată
        val headLen = len * 0.2f
        val angle1 = rad + Math.PI * 5 / 6
        val angle2 = rad - Math.PI * 5 / 6
        canvas.drawLine(ex, ey, ex + headLen * sin(angle1).toFloat(), ey - headLen * cos(angle1).toFloat(), paint)
        canvas.drawLine(ex, ey, ex + headLen * sin(angle2).toFloat(), ey - headLen * cos(angle2).toFloat(), paint)
    }
}
