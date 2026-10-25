package com.misaka.timelog.data.import

import com.misaka.timelog.data.entity.RecordEntity
import com.misaka.timelog.data.entity.RecordSource
import com.misaka.timelog.data.entity.RecordTagEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把「现在的整张 records 表」编成一串 JSON，也能反过来解 —— 后悔药的编码器。
 *
 * ## 为什么用 `org.json` 而不是引一个序列化库
 *
 * 这个 App 的 JSON 需求只有两处（这里是其一，另一处是导出文件）。
 * 为两处引一个注解处理器 + 一个 runtime，换来的只是少写几十行 ——
 * 而 `org.json` 是 Android 系统自带的，零依赖、零版本冲突。
 *
 * ## ⚠️ 它**只**装记录，不是「整包导出」
 *
 * `BackupSnapshotEntity.payload` 那份文档里写的整包格式
 * （`{v,at,why,store,cats,items,tag1,tags,tpl}`）是给「导出 .json / 换手机」
 * 那件事准备的，**还没实现**。这里存的是一份**范围更小**的东西：
 * 只有 `records` 和 `record_tags` —— 因为整库替换干的唯一一件事就是换掉这两张表，
 * 留一份正好能把它还原回去的、最小的后悔药。
 *
 * 为了让两者将来不会认错格式，这里写了 `kind` 加以区分。
 * 键名一律用**短名**：这份 payload 有可能装几千条记录，长键名纯粹是浪费。
 */
internal object RecordSnapshot {

    /** 这份快照是什么 —— 跟将来的「整包导出」区分开。 */
    const val KIND = "records-before-replace"

    /** 格式版本。改了编码方式就 +1，解码时对不上直接拒绝，别硬猜。 */
    const val VERSION = 1

    /** 解出来的东西。 */
    data class Payload(
        val at: Long,
        val why: String,
        val records: List<RecordEntity>,
        val refs: List<RecordTagEntity>,
    )

    fun encode(at: Long, why: String, records: List<RecordEntity>, refs: List<RecordTagEntity>): String {
        val root = JSONObject()
        root.put("kind", KIND)
        root.put("v", VERSION)
        root.put("at", at)
        root.put("why", why)

        val rs = JSONArray()
        records.forEach { r ->
            val o = JSONObject()
            o.put("i", r.id)
            o.put("d", r.day)
            o.put("a", r.startMin)
            // 进行中的那条 endMin 是 null —— JSON 里就得是 null，不能写成 0
            o.put("b", r.endMin ?: JSONObject.NULL)
            o.put("t", r.itemId ?: JSONObject.NULL)
            o.put("c", r.categoryIdSnap ?: JSONObject.NULL)
            o.put("s", r.source.name)
            o.put("h", r.chainId ?: JSONObject.NULL)
            o.put("x", r.noTemplate)
            o.put("n", r.note)
            o.put("u", r.updatedAt)
            rs.put(o)
        }
        root.put("records", rs)

        val ts = JSONArray()
        refs.forEach { ref ->
            ts.put(JSONArray().put(ref.recordId).put(ref.tagId))
        }
        root.put("tags", ts)

        return root.toString()
    }

    /** 解不出来返回 `null` —— 调用方给一句「快照读不出来」的人话，不要让它崩。 */
    fun decode(text: String): Payload? = runCatching {
        val root = JSONObject(text)
        require(root.optString("kind") == KIND) { "不是记录快照" }
        require(root.optInt("v", -1) == VERSION) { "快照版本对不上" }

        val records = mutableListOf<RecordEntity>()
        val rs = root.getJSONArray("records")
        for (k in 0 until rs.length()) {
            val o = rs.getJSONObject(k)
            // ⚠️ 来源按**名字**存（Room 对 enum 的默认行为也是存名字）。
            //    存序数的话，将来往 enum 中间插一个取值，所有历史数据的含义就错位了。
            val source = RecordSource.entries.firstOrNull { it.name == o.getString("s") }
                ?: error("认不出的记录来源：${o.getString("s")}")
            records += RecordEntity(
                id = o.getString("i"),
                day = o.getString("d"),
                startMin = o.getInt("a"),
                endMin = if (o.isNull("b")) null else o.getInt("b"),
                itemId = if (o.isNull("t")) null else o.getString("t"),
                categoryIdSnap = if (o.isNull("c")) null else o.getString("c"),
                source = source,
                chainId = if (o.isNull("h")) null else o.getString("h"),
                noTemplate = o.getBoolean("x"),
                note = o.optString("n", ""),
                updatedAt = o.getLong("u"),
                // 快照一律取「还活着的行」，所以这里恒 false
                deleted = false,
            )
        }

        val refs = mutableListOf<RecordTagEntity>()
        val ts = root.optJSONArray("tags")
        if (ts != null) {
            for (k in 0 until ts.length()) {
                val pair = ts.getJSONArray(k)
                refs += RecordTagEntity(recordId = pair.getString(0), tagId = pair.getString(1))
            }
        }

        Payload(
            at = root.optLong("at", 0L),
            why = root.optString("why", ""),
            records = records,
            refs = refs,
        )
    }.getOrNull()
}
