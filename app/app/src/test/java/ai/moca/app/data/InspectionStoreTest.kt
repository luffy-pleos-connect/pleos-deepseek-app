package ai.moca.app.data

import moca.core.Category
import moca.core.Color
import moca.core.Demo
import moca.core.FluidLevel
import moca.core.Item
import moca.core.Profile
import moca.core.Rules
import moca.core.Scoring
import moca.core.Telltale
import moca.core.TelltaleState
import moca.core.VehicleSnapshot
import moca.core.WheelArea
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

/**
 * 개발 문서 W2·W3: 점검 기록 저장소와 관리 점수. 최종정리 2.4절 5번, 2.5절, 3장 2번, 개발 문서 7장.
 * lead 가 쓴 테스트다. 워커는 이 파일을 고치지 않는다.
 *
 * 약속한 모양(ai.moca.app.data):
 *   class InspectionStore(dir: File, now: () -> Long = { System.currentTimeMillis() })
 *   data class StoredInspection(profile: Profile, date: LocalDate, seq: Int, atMillis: Long,
 *                               scenarioId: String?, snapshot: VehicleSnapshot, distanceDriven: Double?)
 *   append(profile, snapshot, today, scenarioId = null, distanceDriven = null): StoredInspection
 *   all(profile): List<StoredInspection>        시간순
 *   records(profile): List<moca.core.Inspection> 엔진(Care)에 넣는 모양. 순서는 all 과 같다
 *   latest(profile): StoredInspection?
 *   care(profile, today): Int                    Care.evaluate(records, profile, today).score
 *   demoInspect(scenarioId, today): StoredInspection
 *       데모 파일이 비었거나, 다른 시나리오의 것이거나, 마지막 기록이 오늘이 아니면 Demo.recordsOf(id, today) 로 새로 깐다.
 *       이미 오늘 이 시나리오로 깔려 있으면 같은 스냅샷으로 한 줄 더 붙인다(같은 날 순번 +1).
 *   demoResolveD3(today): StoredInspection       Demo.resolvedD3(today) 의 마지막 스냅샷을 한 줄 붙인다
 *   clear()                                      두 파일을 지운다
 * 파일: dir/inspections_real.json, dir/inspections_demo.json (JSON 배열, 필드는 개발 문서 7장)
 */
class InspectionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val today: LocalDate = LocalDate.of(2026, 11, 14)
    private var clock = 1_794_600_000_000L
    private fun store(dir: File = tmp.root) = InspectionStore(dir) { clock }

    private val messy = VehicleSnapshot(
        engineOil = FluidLevel.HIGH,
        transmissionOil = FluidLevel.LOW,
        brakeFluid = FluidLevel.CRITICALLY_LOW,
        lowTireWheels = setOf(WheelArea.FL, WheelArea.RR),
        fuelPercent = null,
        batteryPercent = 55.5,
        washerFluidLow = true,
        airbagDeployed = true,
        telltales = listOf(TelltaleState(Telltale.ABS, Color.RED, blinking = true), TelltaleState(Telltale.TPMS, Color.YELLOW)),
        unsupported = setOf(Item.WASHER_FLUID, Item.AIRBAG),
    )

    private fun evaluate(snapshot: VehicleSnapshot, care: Int) = Scoring.evaluate(snapshot, care, Rules.hasRed(snapshot))

    // ── 저장과 읽기 ───────────────────────────────────────────────────

    @Test
    fun `저장한 스냅샷을 그대로 읽는다`() {
        val s = store()
        val saved = s.append(Profile.REAL, messy, today, distanceDriven = 12345.6)
        assertEquals(messy, saved.snapshot)
        val again = store().latest(Profile.REAL)!!
        assertEquals(messy, again.snapshot)
        assertEquals(today, again.date)
        assertEquals(1, again.seq)
        assertEquals(clock, again.atMillis)
        assertNull(again.scenarioId)
        assertEquals(12345.6, again.distanceDriven!!, 0.001)
        assertEquals(Profile.REAL, again.profile)
    }

    @Test
    fun `같은 날 다시 점검하면 순번이 오르고 다음 날은 1부터다`() {
        val s = store()
        assertEquals(1, s.append(Profile.REAL, VehicleSnapshot.normal(), today).seq)
        clock += 60_000
        assertEquals(2, s.append(Profile.REAL, messy, today).seq)
        assertEquals(1, s.append(Profile.REAL, VehicleSnapshot.normal(), today.plusDays(1)).seq)
        assertEquals(listOf(1, 2, 1), s.all(Profile.REAL).map { it.seq })
        assertEquals(3, s.records(Profile.REAL).size)
        assertEquals(listOf(today, today, today.plusDays(1)), s.records(Profile.REAL).map { it.inspectedAt })
    }

    @Test
    fun `기록이 없으면 최근 기록도 없다`() {
        assertNull(store().latest(Profile.REAL))
        assertNull(store().latest(Profile.DEMO))
        assertTrue(store().all(Profile.REAL).isEmpty())
    }

    @Test
    fun `파일은 개발 문서 7장의 모양이다`() {
        store().append(Profile.REAL, messy, today, distanceDriven = 7.0)
        val file = File(tmp.root, "inspections_real.json")
        assertTrue(file.exists())
        assertFalse("데모 파일은 실제 점검으로 생기지 않는다", File(tmp.root, "inspections_demo.json").exists())
        val row = JSONArray(file.readText()).getJSONObject(0)
        assertEquals("REAL", row.getString("profile"))
        assertEquals("2026-11-14", row.getString("date"))
        assertEquals(1, row.getInt("seq"))
        assertEquals(clock, row.getLong("atMillis"))
        assertTrue(row.isNull("scenarioId"))
        assertEquals(7.0, row.getDouble("distanceDriven"), 0.001)
        val snap = row.getJSONObject("snapshot")
        assertEquals("HIGH", snap.getString("engineOil"))
        assertEquals("LOW", snap.getString("transmissionOil"))
        assertEquals("CRITICALLY_LOW", snap.getString("brakeFluid"))
        assertEquals(setOf("FL", "RR"), (0 until snap.getJSONArray("lowTireWheels").length()).map { snap.getJSONArray("lowTireWheels").getString(it) }.toSet())
        assertTrue(snap.isNull("fuelPercent"))
        assertEquals(55.5, snap.getDouble("batteryPercent"), 0.001)
        assertTrue(snap.getBoolean("washerFluidLow"))
        assertTrue(snap.getBoolean("airbagDeployed"))
        val abs = snap.getJSONArray("telltales").getJSONObject(0)
        assertEquals("ABS", abs.getString("telltale"))
        assertEquals("RED", abs.getString("color"))
        assertTrue(abs.getBoolean("blinking"))
        assertEquals(setOf("WASHER_FLUID", "AIRBAG"), (0 until snap.getJSONArray("unsupported").length()).map { snap.getJSONArray("unsupported").getString(it) }.toSet())
    }

    @Test
    fun `깨진 파일은 빈 기록으로 본다`() {
        File(tmp.root, "inspections_real.json").writeText("{쓰다 만")
        val s = store()
        assertTrue(s.all(Profile.REAL).isEmpty())
        assertEquals(1, s.append(Profile.REAL, VehicleSnapshot.normal(), today).seq)
    }

    @Test
    fun `실제 기록은 90일만 남기고 데모 기록은 그대로 둔다`() {
        val s = store()
        s.append(Profile.REAL, VehicleSnapshot.normal(), today.minusDays(91))
        s.append(Profile.REAL, VehicleSnapshot.normal(), today.minusDays(90))
        s.append(Profile.REAL, VehicleSnapshot.normal(), today)
        assertEquals(listOf(today.minusDays(90), today), s.all(Profile.REAL).map { it.date })
        s.demoInspect("D3", today)
        assertEquals(Demo.recordsOf("D3", today).size, s.all(Profile.DEMO).size)
    }

    @Test
    fun `지우면 두 파일이 모두 없어진다`() {
        val s = store()
        s.append(Profile.REAL, messy, today)
        s.demoInspect("D0", today)
        s.clear()
        assertFalse(File(tmp.root, "inspections_real.json").exists())
        assertFalse(File(tmp.root, "inspections_demo.json").exists())
        assertNull(s.latest(Profile.REAL))
        assertNull(s.latest(Profile.DEMO))
    }

    // ── 관리 점수 (B-07) ──────────────────────────────────────────────

    @Test
    fun `실제 관리 점수는 기록으로 계산한다`() {
        val s = store()
        assertEquals("기록이 없으면 이번 달 시작값", 50, s.care(Profile.REAL, today))
        s.append(Profile.REAL, VehicleSnapshot.normal(), today)
        assertEquals("점검한 날 +2", 52, s.care(Profile.REAL, today))
        s.append(Profile.REAL, VehicleSnapshot.normal(), today)
        assertEquals("같은 날 다시 점검해도 +2 는 한 번", 52, s.care(Profile.REAL, today))
    }

    @Test
    fun `실제에서 고친 뒤 같은 날 다시 점검하면 해소 점수가 붙는다`() {
        val s = store()
        val abs = VehicleSnapshot.normal().copy(telltales = listOf(TelltaleState(Telltale.ABS, Color.RED)))
        s.append(Profile.REAL, abs, today)
        val before = s.care(Profile.REAL, today)
        s.append(Profile.REAL, VehicleSnapshot.normal(), today)
        assertEquals("감점 원인이 사라지면 +5", before + 5, s.care(Profile.REAL, today))
    }

    // ── 데모 (B-10, 최종정리 2.5절) ───────────────────────────────────

    @Test
    fun `데모 시나리오 일곱 개가 최종정리 2_5절 값과 같다`() {
        val expected = mapOf(
            "D0" to ("A" to 85), "D1" to ("A" to 80), "D2" to ("A" to 80), "D3" to ("B" to 75),
            "D4" to ("A" to 84), "D5" to ("B" to 76), "D6" to ("A" to 85),
        )
        for ((id, want) in expected) {
            val s = store(tmp.newFolder(id))
            val saved = s.demoInspect(id, today)
            assertEquals(id, Demo.snapshotOf(id), saved.snapshot)
            assertEquals(id, saved.scenarioId)
            assertEquals(Profile.DEMO, saved.profile)
            assertEquals(today, saved.date)
            val care = s.care(Profile.DEMO, today)
            assertEquals("$id 관리 점수", 53, care)
            val e = evaluate(saved.snapshot, care)
            assertEquals("$id 등급", want.first, e.grade!!.label)
            assertEquals("$id 종합", want.second, e.displayedOverall)
        }
    }

    @Test
    fun `데모 D3 구역 점수는 100 50 75 100 100 이다`() {
        val s = store()
        val e = evaluate(s.demoInspect("D3", today).snapshot, s.care(Profile.DEMO, today))
        assertEquals(listOf(100, 50, 75, 100, 100), Category.entries.map { e.categories[it] })
        assertTrue(e.redCap)
    }

    @Test
    fun `데모에서 같은 날 다시 점검하면 한 줄이 붙고 관리 점수는 그대로다`() {
        val s = store()
        val first = s.demoInspect("D3", today)
        val base = s.all(Profile.DEMO).size
        clock += 5_000
        val second = s.demoInspect("D3", today)
        assertEquals(base + 1, s.all(Profile.DEMO).size)
        assertEquals(first.seq + 1, second.seq)
        assertEquals(first.snapshot, second.snapshot)
        assertEquals(clock, s.latest(Profile.DEMO)!!.atMillis)
        assertEquals(53, s.care(Profile.DEMO, today))
    }

    @Test
    fun `데모 시나리오를 바꾸면 기록을 새로 깐다`() {
        val s = store()
        s.demoInspect("D3", today)
        s.demoInspect("D3", today)
        val saved = s.demoInspect("D1", today)
        assertEquals(Demo.recordsOf("D1", today).size, s.all(Profile.DEMO).size)
        assertEquals("D1", saved.scenarioId)
        assertEquals(Demo.snapshotOf("D1"), s.latest(Profile.DEMO)!!.snapshot)
    }

    @Test
    fun `데모 기록은 날이 바뀌면 오늘 기준으로 다시 깐다`() {
        val s = store()
        s.demoInspect("D3", today)
        val tomorrow = today.plusDays(1)
        val saved = s.demoInspect("D3", tomorrow)
        assertEquals(tomorrow, saved.date)
        assertEquals(Demo.recordsOf("D3", tomorrow).size, s.all(Profile.DEMO).size)
        assertEquals(53, s.care(Profile.DEMO, tomorrow))
    }

    @Test
    fun `데모 D3 에서 ABS 를 해결하면 관리 58 이고 A 83 이다`() {
        val s = store()
        s.demoInspect("D3", today)
        val fixed = s.demoResolveD3(today)
        assertEquals(Demo.resolvedD3(today).last().snapshot, fixed.snapshot)
        assertEquals(today, fixed.date)
        val care = s.care(Profile.DEMO, today)
        assertEquals("해소 1묶음 +5", 58, care)
        val e = evaluate(fixed.snapshot, care)
        assertEquals("A", e.grade!!.label)
        assertEquals(83, e.displayedOverall)
        assertFalse(e.redCap)
        assertNotNull(s.latest(Profile.DEMO))
        assertEquals(fixed.snapshot, store().latest(Profile.DEMO)!!.snapshot)
    }

    @Test
    fun `실제 기록과 데모 기록은 서로 섞이지 않는다`() {
        val s = store()
        s.append(Profile.REAL, messy, today)
        s.demoInspect("D0", today)
        assertEquals(1, s.all(Profile.REAL).size)
        assertTrue(s.all(Profile.REAL).all { it.profile == Profile.REAL })
        assertTrue(s.all(Profile.DEMO).all { it.profile == Profile.DEMO })
        assertEquals(52, s.care(Profile.REAL, today))
        assertEquals(53, s.care(Profile.DEMO, today))
    }
}
