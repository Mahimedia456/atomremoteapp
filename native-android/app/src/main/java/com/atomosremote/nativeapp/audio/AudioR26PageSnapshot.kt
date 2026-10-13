package com.atomosremote.nativeapp.audio

/** R26.19: read-only decoding of the HW-confirmed 0x0647 -> 0x8647 page.
 * The *property NAMES* are anchored to macOS Swift __swift5_fieldmd, record
 * offset 0x39364c (x86_64 slice) in Contents(20261011-114525).zip.
 * The *tag associations* are STRUCTURAL INFERENCES from field types and the
 * 39/40-byte recorder payloads, not hardware-validated control calibration.
 * No individual Audio GET or SET is ever produced here.
 */
object AudioR26PageSnapshot {
    data class Readback(
        val monitor: Long, val phonesLevel: Long,
        val recordEnabledList: List<Long>, val recordValidList: List<Long>,
        val monitorList: List<Long>, val analogEmbedList: List<Long>,
        val phonesLevelMin: Long, val phonesLevelMax: Long,
        val orientation: Long, val orientationChangeable: Long, val analogEmbed: Long
    ) {
        val rows: List<Pair<String, String>> get() = listOf(
            "Monitor selector" to "$monitor (raw)",
            "Headphones level" to "$phonesLevel (raw; range $phonesLevelMin..$phonesLevelMax)",
            "Record enabled list" to recordEnabledList.joinToString(", ", "[", "]"),
            "Record valid list" to recordValidList.joinToString(", ", "[", "]"),
            "Monitor options" to monitorList.joinToString(", ", "[", "]"),
            "Analog embed options" to analogEmbedList.joinToString(", ", "[", "]"),
            "Orientation" to "$orientation (raw)",
            "Orientation changeable" to "$orientationChangeable (raw)",
            "Analog embed" to "$analogEmbed (raw)"
        )
    }

    private fun varint(bytes: ByteArray, from: Int): Pair<Long, Int>? {
        var p = from; var v = 0L; var shift = 0
        while (p < bytes.size && shift <= 63) {
            val c = bytes[p++].toInt() and 255
            if (shift == 63 && c > 1) return null
            v = v or ((c.toLong() and 127) shl shift)
            if (c and 128 == 0) return v to p
            shift += 7
        }
        return null
    }
    fun decode(hex: String): Readback? {
        if (hex.length !in 60..160 || hex.length % 2 != 0 ||
            !hex.all { it in "0123456789abcdefABCDEF" }) return null
        val b = ByteArray(hex.length / 2) { i -> hex.substring(2*i, 2*i+2).toInt(16).toByte() }
        val scalars = mutableMapOf<Int,Long>()
        val packed = mutableMapOf<Int,List<Long>>()
        var pos = 0; var fields = 0
        while (pos < b.size) {
            if (++fields > 11) return null
            val t = varint(b,pos) ?: return null; pos = t.second
            val n = (t.first ushr 3).toInt(); val wire = (t.first and 7).toInt()
            if (n !in 1..11 || n in scalars || n in packed) return null
            when {
                n in setOf(1,2,7,8,9,10,11) && wire == 0 -> {
                    val v=varint(b,pos) ?: return null;pos=v.second;scalars[n]=v.first
                }
                n in 3..6 && wire == 2 -> {
                    val len=varint(b,pos) ?: return null;pos=len.second
                    if(len.first !in 0..16 || len.first > (b.size-pos))return null
                    val end=pos+len.first.toInt();val values=mutableListOf<Long>()
                    while(pos<end){val v=varint(b,pos) ?: return null
                        if(v.second>end || v.first>255)return null
                        pos=v.second;values.add(v.first)}
                    packed[n]=values
                }
                else -> return null
            }
        }
        if(fields!=11 || scalars.size!=7 || packed.size!=4 || pos!=b.size) return null
        val min=scalars[7]!!;val max=scalars[8]!!;val lvl=scalars[2]!!
        if(min>max || lvl !in min..max || scalars[10] !in 0L..1L) return null
        return Readback(scalars[1]!!,lvl,packed[3]!!,packed[4]!!,packed[5]!!,packed[6]!!,
            min,max,scalars[9]!!,scalars[10]!!,scalars[11]!!)
    }
    fun rowLabel(key:String,hex:String): String? {
        val s=decode(hex) ?: return null
        return when(key){
            "phones" -> "Recorder page: ${s.phonesLevel} / ${s.phonesLevelMax} (raw scale; field mapping inferred)"
            "analogMonitor" -> "Recorder page monitor selector=${s.monitor} (raw; inferred)"
            "analogRecord" -> "Recorder page REC-enabled list=${s.recordEnabledList} (index meanings pending)"
            "orientation" -> "Recorder page orientation=${s.orientation}, changeable=${s.orientationChangeable} (raw; inferred)"
            "embed" -> "Recorder page analog embed=${s.analogEmbed} (raw; inferred)"
            else -> null
        }
    }
}
