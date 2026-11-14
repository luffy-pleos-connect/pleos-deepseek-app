package ai.moca.app.vehicle

import moca.core.Telltale

/**
 * SDK 경고등 종류(`TelltaleType.name`) → :core [Telltale] 대응표.
 *
 * 표는 이 한 곳에만 둔다(개발문서 6장 1번). 대소문자를 가리지 않으며,
 * 대응이 없는 종류는 MASTER_WARNING 으로 뭉개지 않고 **null 로 버린다** —
 * 엉뚱한 구역에 감점을 넣으면 점수가 틀린다.
 */
object TelltaleTypeMap {

    fun toEngine(type: String): Telltale? = when (type.uppercase()) {
        "ENGINE" -> Telltale.ENGINE
        "ENGINE_OIL_PRESSURE" -> Telltale.ENGINE_OIL_PRESSURE
        "ENGINE_TEMPERATURE" -> Telltale.ENGINE_TEMPERATURE
        "EV_SYSTEM" -> Telltale.EV_SYSTEM
        "HEV_SYSTEM" -> Telltale.HEV_SYSTEM
        "PARTICLE_FILTER" -> Telltale.PARTICLE_FILTER
        "ABS" -> Telltale.ABS
        "ESC" -> Telltale.ESC
        "MDPS" -> Telltale.MDPS
        "TPMS" -> Telltale.TPMS
        "LOW_VOLT_BATTERY" -> Telltale.LOW_VOLT_BATTERY
        "POWER_DOWN" -> Telltale.POWER_DOWN
        "AIRBAG" -> Telltale.AIRBAG
        "MASTER_WARNING" -> Telltale.MASTER_WARNING
        "LOW_FUEL" -> Telltale.LOW_FUEL
        "LOW_EV_BATTERY" -> Telltale.LOW_EV_BATTERY
        "SEAT_BELT" -> Telltale.SEATBELT
        "PASSENGER_AIRBAG_OFF" -> Telltale.PASSENGER_AIRBAG_OFF
        else -> null
    }
}
