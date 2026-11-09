package moca.core

data class Seed(
    val nickname: String,
    val power: Int,
    val brake: Int,
    val tire: Int,
    val electric: Int,
    val safety: Int,
    val care: Int,
    val overall: Double,
    val grade: Grade,
    val demoOnly: Boolean = false,
    val redCap: Boolean = false,
) {
    val categories: Map<Category, Int?>
        get() = mapOf(
            Category.POWER to power,
            Category.BRAKE_STEERING to brake,
            Category.TIRE to tire,
            Category.ELECTRIC to electric,
            Category.SAFETY to safety,
        )

    val health: Double get() = categories.values.filterNotNull().average()

    fun evaluation(): Evaluation = Scoring.evaluate(categories, care, redCap)
}

object Seeds {
    private const val RESOURCE = "/moca/core/seeds.json"

    val all: List<Seed> by lazy { load() }

    val battlePool: List<Seed> get() = all.filter { !it.demoOnly }

    fun byNickname(nickname: String): Seed? = all.firstOrNull { it.nickname == nickname }

    fun load(): List<Seed> {
        val text = Seeds::class.java.getResourceAsStream(RESOURCE)?.bufferedReader()?.use { it.readText() }
            ?: error("시드 리소스를 찾을 수 없습니다: $RESOURCE")
        val root = Json.parse(text) as Map<*, *>
        val list = root["seeds"] as List<*>
        return list.map { entry ->
            val map = entry as Map<*, *>
            Seed(
                nickname = map["nickname"] as String,
                power = (map["power"] as Double).toInt(),
                brake = (map["brake"] as Double).toInt(),
                tire = (map["tire"] as Double).toInt(),
                electric = (map["electric"] as Double).toInt(),
                safety = (map["safety"] as Double).toInt(),
                care = (map["care"] as Double).toInt(),
                overall = map["overall"] as Double,
                grade = Grade.valueOf(map["grade"] as String),
                demoOnly = map["demoOnly"] as? Boolean ?: false,
                redCap = map["redCap"] as? Boolean ?: false,
            )
        }
    }
}
