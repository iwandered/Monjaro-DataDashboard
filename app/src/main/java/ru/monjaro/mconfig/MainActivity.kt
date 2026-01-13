package ru.monjaro.mconfig

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View.GONE
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MainActivity : FragmentActivity() {

    private var preferences:SharedPreferences?=null

    // 固定 DPI 为 320 (xhdpi)
    override fun attachBaseContext(newBase: Context) {
        val configuration = Configuration(newBase.resources.configuration)
        configuration.densityDpi = 320 // 固定 DPI
        configuration.fontScale = 1.0f // 固定字体大小
        val context = newBase.createConfigurationContext(configuration)
        super.attachBaseContext(context)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 忽略密度变化，保持固定设置
        val fixedConfig = Configuration(newConfig)
        fixedConfig.densityDpi = 320
        fixedConfig.fontScale = 1.0f
        applyOverrideConfiguration(fixedConfig)
    }

    fun checkStoragePermissions():Boolean{
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
            //Android is 11 (R) or above
            return Environment.isExternalStorageManager();
        }else {
            //Below android 11
            val write = ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
            val read = ContextCompat.checkSelfPermission(this,  android.Manifest.permission.READ_EXTERNAL_STORAGE);

            return read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED;
        }
    }

    private val STORAGE_PERMISSION_CODE = 23
    private fun requestForStoragePermissions() {
        //Android is 11 (R) or above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent()
                intent.setAction(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", this.packageName, null)
                intent.setData(uri)
                startActivity(intent)
            } catch (e: java.lang.Exception) {
                val intent = Intent()
                intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            //Below android 11
            ActivityCompat.requestPermissions(
                this, arrayOf<String>(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ),
                STORAGE_PERMISSION_CODE
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 确保左上角对齐
        ensureTopLeftAlignment()

        if (intent != null && intent.action != null && intent.action == "android.intent.action.VIEW" && intent.data != null) {
            val data = intent.data
            val uri = data.toString()
            if(uri.endsWith(".mcf")) {
                var ret = false
                if (data != null) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
                    if (prefs != null) {
                        applicationContext.contentResolver.openInputStream(data).use {
                            if (it != null) {
                                ret = sharedPreferencesFromString(prefs, it.bufferedReader().readText())
                            }
                        }
                    }
                    if(!ret){
                        Toast.makeText(applicationContext, "Ошибка импорта настроек", Toast.LENGTH_LONG).show()
                    }else{
                        Toast.makeText(applicationContext, "Настройки импортированы", Toast.LENGTH_LONG).show()
                    }
                }
                val pm = applicationContext.packageManager
                val intent = pm.getLaunchIntentForPackage(applicationContext.packageName)
                val mainIntent = Intent.makeRestartActivityTask(intent!!.component)
                applicationContext.startActivity(mainIntent)
            }
        }

        setContentView(R.layout.main_layout)

        val versionName = findViewById<TextView>(R.id.VersionText)
        versionName.text = ""
        versionName.textSize = 24f
        versionName.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
        versionName.gravity = android.view.Gravity.CENTER

        // 分别设置各个方向的边距
        val leftPadding = (50 * resources.displayMetrics.density).toInt()   // 左侧110dp
        val topPadding = 0                                                  // 顶部0dp
        val rightPadding = (70 * resources.displayMetrics.density).toInt()   // 右侧80dp（可调整这个值）
        val bottomPadding = 0                                               // 底部0dp

        versionName.setPadding(leftPadding, topPadding, rightPadding, bottomPadding)

        // 直接加载 DataFragment
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, DataFragment())
                .commit()
        }

        /***************permissions****************/
        val intent = Intent()
        val packageName = packageName
        val pm: PowerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.setData(Uri.parse("package:$packageName"))
            startActivity(intent)
        }
        if(!checkStoragePermissions()){
            requestForStoragePermissions()
        }
        /********************************************************/

        preferences = PreferenceManager.getDefaultSharedPreferences(this);
        val enSwitch = findViewById<SwitchMaterial>(R.id.startServiceSwitch)
        enSwitch.setOnCheckedChangeListener { _, isChecked ->
            if(!stopWithError && preferences?.getBoolean(IdNames.StartService_key, false) != isChecked){
                preferences?.edit()?.putBoolean(IdNames.StartService_key, isChecked)?.apply()
            }
            stopWithError = false
            if (isChecked) {
                MConfigStartProc.startService(applicationContext, true)
            }else{
                MConfigStartProc.stopService(applicationContext)
            }
        }

        preferences?.getBoolean(IdNames.StartService_key, false)?.let { enSwitch.isChecked = it }

        instance = this
        prefs = preferences

        // 如果存在无障碍服务警告，可以在这里处理
        // if (PnAccessibilityService.accessibilityStarted) {
        //     val warnText = findViewById<TextView>(R.id.warningAccessibilityText)
        //     warnText.visibility = GONE
        // }

        //request update data views
        try {
            Handler(Looper.getMainLooper()).postDelayed({
                MConfigManager.sendMessageToThread(IdNames.UPDATE_UI, "UPDATE_UI")
            }, 500)
        }catch(_:Exception){}
    }

    private fun ensureTopLeftAlignment() {
        // 防止系统缩放
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
            window.decorView.scaleX = 1.0f
            window.decorView.scaleY = 1.0f
        }

        // 设置左上角对齐
        window.setGravity(android.view.Gravity.TOP or android.view.Gravity.START)
    }

    override fun onResume() {
        super.onResume()
        try {
            MConfigManager.sendMessageToThread(IdNames.UPDATE_UI, "UPDATE_UI")
        }catch(_:Exception){}
    }

    companion object {
        var stopWithError: Boolean = false
        var instance: MainActivity? = null
        var prefs: SharedPreferences? = null;
        var debugToastEnabled: Int = -1

        fun getApplicationsList(): ArrayList<String> {
            val packageManager: PackageManager? = instance?.packageManager;
            val packages =
                packageManager?.queryIntentActivities(Intent("android.intent.action.MAIN"), 0);
            val arrayListLabel: ArrayList<String> = ArrayList();

            arrayListLabel.add("---")

            if (packages != null) {
                for (p in packages) {
                    arrayListLabel.add(
                        String.format(
                            "%s [%s / %s]",
                            packageManager.getApplicationLabel(p.activityInfo.applicationInfo),
                            p.activityInfo.packageName,
                            p.activityInfo.name
                        )
                    )
                }
            }
            arrayListLabel.sort();
            return arrayListLabel
        }

        fun debugToast(context: Context, s: String?) {
            try {
                if (debugToastEnabled == -1) {
                    val prefs = context.let { PreferenceManager.getDefaultSharedPreferences(it) };
                    if (prefs != null) {
                        val b = prefs.getBoolean(IdNames.debugToast_key, false)
                        debugToastEnabled = if (b) {
                            1
                        } else {
                            0
                        }
                    }
                }
            }catch(_:Exception){}
            if(debugToastEnabled==1){
                Toast.makeText(context,s,Toast.LENGTH_SHORT).show()
            }
        }

        @Serializable
        class settingsModel(val key: String, val type: String, val value: String)

        fun sharedPreferencesFromString(prefs: SharedPreferences?, s:String?): Boolean{
            try {
                try {
                    val settingsList = s?.let { Json.decodeFromString<Array<settingsModel>>(it) }

                    if (settingsList != null && prefs != null) {
                        settingsList.forEach { it1 ->
                            when(it1.type){
                                "String" -> prefs.edit().putString(it1.key, it1.value)?.apply()

                                "Boolean" -> {
                                    val b = it1.value.toBoolean()
                                    prefs.edit().putBoolean(it1.key, it1.value.toBoolean())?.apply()
                                }
                                "Float" -> prefs.edit().putFloat(it1.key, it1.value.toFloat())?.apply()
                                "Int" -> prefs.edit().putInt(it1.key, it1.value.toInt())?.apply()
                            }
                        }
                        return true
                    }
                } catch(_:Exception) {
                    // 处理异常
                }
            } catch(_:Error) {
                // 处理错误
            }
            return false
        }

        fun sharedPreferencesToString( prefs: SharedPreferences?) :String{
            val settingsList = mutableListOf<settingsModel>()
            val settingMap = prefs?.all

            settingMap?.forEach lit@{ it1 ->
                var type = "String"
                if(it1.key == IdNames.StartService_key){
                    return@lit
                }
                if (settingMap[it1.key] is String) {
                    type = "String"
                } else if (settingMap[it1.key] is Boolean) {
                    type = "Boolean"
                } else if (settingMap[it1.key] is Float) {
                    type = "Float"
                } else if (settingMap[it1.key] is Int) {
                    type = "Int"
                }
                val el = settingsModel(it1.key, type, it1.value.toString())
                settingsList.add(el)
            }
            return Json.encodeToString(settingsList)
        }

        // 仅仅为了编译通过的空方法
        @JvmStatic
        fun setUpdatedData(what: Int, data: String) {
            // 什么都不做，只是为了编译通过
        }
    }
}