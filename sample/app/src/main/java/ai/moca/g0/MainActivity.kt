package ai.moca.g0

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.telltale.TelltaleData
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView

/**
 * MOCA G0 스파이크: Vehicle SDK가 실제 값을 주는지 로그로 확인한다.
 *
 * 수집 항목 (계획 14장 G0):
 *  1. Safety.registerTelltales 콜백: type / state / color(Int) / priority / warningCount
 *  2. Safety.getTelltalesInfo 1회
 *  3. EvBattery.getEvBatteryStateOfCharge 1회
 *  4. 보조: 오일·브레이크액 반환형, 타이어 저압, capability 응답
 *
 * 모든 로그 태그는 MOCA_G0 하나로 모은다: adb logcat -d -s MOCA_G0
 */
class MainActivity : Activity() {

    private lateinit var vehicle: Vehicle
    private lateinit var output: TextView
    private val lines = StringBuilder()

    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(telltales: Telltales?) {
            val list = telltales?.telltales ?: emptyList()
            log("LISTENER count=${list.size}")
            for (t in list) {
                logTelltale("LISTENER", t)
            }
            ui("리스너: 경고등 ${list.size}개")
        }

        override fun onFailed(e: Exception) {
            log("LISTENER FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            textSize = 28f
            gravity = Gravity.START
            setPadding(48, 48, 48, 48)
        }
        setContentView(output)
        ui("Vehicle SDK 초기화 중…")

        vehicle = Vehicle(this)
        vehicle.initialize()
        log("INIT Vehicle.initialize() called")

        registerAndReadTelltales()
        readTelltalesOnce()
        readSocOnce()
        readOthersOnce()
    }

    private fun registerAndReadTelltales() {
        try {
            vehicle.getSafety().registerTelltales(telltaleListener)
            log("REGISTER registerTelltales OK")
        } catch (e: Exception) {
            log("REGISTER registerTelltales FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun readTelltalesOnce() {
        vehicle.getSafety().getTelltalesInfo(
            { telltales: Telltales ->
                val list = telltales.telltales
                log("GET_INFO count=${list.size}")
                for (t in list) {
                    logTelltale("GET_INFO", t)
                }
                ui("getTelltalesInfo: ${list.size}개")
            },
            { e: Exception ->
                log("GET_INFO FAIL ${e.javaClass.simpleName}: ${e.message}")
                ui("getTelltalesInfo 실패: ${e.message}")
            },
        )
    }

    private fun readSocOnce() {
        try {
            vehicle.getEvBattery().getEvBatteryStateOfCharge(
                { soc: Int? ->
                    log("SOC value=$soc")
                    ui("SOC: $soc%")
                },
                { e: Exception -> log("SOC FAIL ${e.javaClass.simpleName}: ${e.message}") },
            )
        } catch (e: Exception) {
            log("SOC THROW ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun readOthersOnce() {
        val safety = vehicle.getSafety()
        safety.getEngineOilLevelState(
            { oil -> log("OIL enum=${oil?.name}") },
            { e -> log("OIL FAIL ${e.javaClass.simpleName}: ${e.message}") },
        )
        safety.getBrakeFluidLevelState(
            { level -> log("BRAKE_FLUID enum=${level?.name}") },
            { e -> log("BRAKE_FLUID FAIL ${e.javaClass.simpleName}: ${e.message}") },
        )
        safety.checkTelltalesCapabilities(
            { cfg -> log("CAP_TELLTALES config=$cfg") },
            { e -> log("CAP_TELLTALES FAIL ${e.javaClass.simpleName}: ${e.message}") },
        )
        vehicle.getTire().checkWheelAreaCapabilities(
            { areas -> log("CAP_WHEEL areas=$areas") },
            { e -> log("CAP_WHEEL FAIL ${e.javaClass.simpleName}: ${e.message}") },
        )
    }

    private fun logTelltale(src: String, t: TelltaleData) {
        log(
            "$src type=${t.type.name} state=${t.state.name} color=${t.color} " +
                "priority=${t.priority} warningCount=${t.warningCount}",
        )
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
    }

    private fun ui(msg: String) {
        runOnUiThread {
            lines.append(msg).append('\n')
            output.text = lines.toString()
        }
    }

    override fun onDestroy() {
        try {
            vehicle.getSafety().unregisterTelltales(telltaleListener)
            log("UNREGISTER ok")
        } catch (e: Exception) {
            log("UNREGISTER FAIL ${e.message}")
        }
        vehicle.release()
        log("RELEASE ok")
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MOCA_G0"
    }
}
