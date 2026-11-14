package ai.moca.app.nav

import org.json.JSONArray
import org.json.JSONObject

/** 최종정리 6장 6번·13장. 플레오스 내비에 없는 «근처 장소 목록»을 앱이 들고 있을 때의 종류. */
enum class PlaceKind(val label: String, val keyword: String) {
    REPAIR_SHOP("정비소", "정비소"),
    TIRE_SHOP("타이어점", "타이어"),
    GAS_STATION("주유소", "주유소"),
    CHARGING_STATION("충전소", "전기차 충전소"),
}

/** 장소 한 곳. 좌표는 내비가 받는 그대로(위도·경도). */
data class Place(
    val kind: PlaceKind,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String,
)

/**
 * res/raw/places_demo.json 을 읽는다. 화면에 이 목록을 쓰면 «데모 데이터» 배지가 붙는다.
 * 깨진 JSON, 모르는 종류, 이름이 빈 줄, 한국 밖 좌표는 버린다.
 */
object Places {
    private val LATITUDE_RANGE = 33.0..39.0
    private val LONGITUDE_RANGE = 124.0..132.0

    fun parse(json: String): List<Place> = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index -> placeOf(array.optJSONObject(index)) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun placeOf(json: JSONObject?): Place? {
        if (json == null) return null
        val kind = PlaceKind.entries.firstOrNull { it.name == json.optString("kind") } ?: return null
        val name = json.optString("name").trim()
        if (name.isEmpty()) return null
        val latitude = json.optDouble("latitude", Double.NaN)
        val longitude = json.optDouble("longitude", Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) return null
        if (latitude !in LATITUDE_RANGE || longitude !in LONGITUDE_RANGE) return null
        return Place(kind, name, latitude, longitude, json.optString("address").trim())
    }
}
