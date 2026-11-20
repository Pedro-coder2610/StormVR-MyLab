package com.stormvr.app.vrroom

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Músicas no formato BeatSaver/BeastSaber (o mesmo que o OpenSaberVR lê): pasta com Info.dat + arquivo .dat da dificuldade + áudio .ogg/.egg.
 * Pasta no celular: Android/data/com.stormvr.app/files/Playlists/<musica>/   (também extrai .zip colocados nessa pasta).
 * Só notas (blocos) — igual ao OpenSaberVR, que ainda não suporta obstáculos nem minas; bombas são ignoradas.
 * Suporta mapas v2 (_notes), v3 (colorNotes) e, sem teste, v4 (colorNotes + colorNotesData).
 */
object SongLibrary {
    class Diff(val name: String, val file: String)
    class Song(val dir: File, val name: String, val artist: String, val bpm: Float, val audio: File?, val cover: File?, val diffs: List<Diff>)
    /** t em segundos; lane 0..3; layer 0..2; color 0 = esquerda/vermelho, 1 = direita/azul; dir 0 cima 1 baixo 2 esq 3 dir 4 cima-esq 5 cima-dir 6 baixo-esq 7 baixo-dir 8 qualquer */
    class Note(val t: Float, val lane: Int, val layer: Int, val color: Int, val dir: Int)
    /** Obstáculo (parede): type 0 = de chão ao teto (desvie para o LADO), type 1 = só em cima (ABAIXE). t e dur em segundos. */
    class Wall(val t: Float, val dur: Float, val lane: Int, val width: Int, val type: Int)

    private val ORDER = listOf("easy", "normal", "hard", "expert", "expertplus")

    fun root(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Playlists").apply { mkdirs() }

    fun scan(ctx: Context): List<Song> {
        val r = root(ctx)
        extractZips(r)
        val out = ArrayList<Song>()
        for (d in (r.listFiles() ?: emptyArray()).filter { it.isDirectory }.sortedBy { it.name.lowercase() }) {
            try { load(d)?.let { out.add(it) } } catch (_: Throwable) { }
        }
        return out
    }

    private fun extractZips(r: File) {
        for (z in (r.listFiles() ?: emptyArray()).filter { it.isFile && it.name.lowercase().endsWith(".zip") }) {
            val target = File(r, z.nameWithoutExtension)
            if (target.exists()) continue
            try {
                target.mkdirs()
                ZipInputStream(z.inputStream().buffered()).use { zin ->
                    var e = zin.nextEntry
                    while (e != null) {
                        val f = File(target, e.name)
                        if (f.canonicalPath.startsWith(target.canonicalPath + File.separator)) {       // evita zip-slip
                            if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zin.copyTo(it) } }
                        }
                        e = zin.nextEntry
                    }
                }
            } catch (_: Throwable) { target.deleteRecursively() }
        }
    }

    private fun find(dir: File, name: String): File? = dir.listFiles()?.firstOrNull { it.name.equals(name, true) }

    fun load(dir: File): Song? {
        val info = find(dir, "info.dat") ?: return null
        val o = JSONObject(info.readText())
        val diffs = ArrayList<Diff>()
        val name: String; val bpm: Float; val audioName: String; val artist: String; val coverName: String
        if (o.has("_songName")) {                                                  // v2
            name = o.optString("_songName", dir.name); bpm = o.optDouble("_beatsPerMinute", 120.0).toFloat(); audioName = o.optString("_songFilename")
            artist = o.optString("_songAuthorName", ""); coverName = o.optString("_coverImageFilename", "")
            val sets = o.optJSONArray("_difficultyBeatmapSets") ?: JSONArray()
            for (i in 0 until sets.length()) {
                val s = sets.getJSONObject(i)
                val ch = s.optString("_beatmapCharacteristicName", "Standard")
                if (ch != "Standard") continue
                val ds = s.optJSONArray("_difficultyBeatmaps") ?: continue
                for (k in 0 until ds.length()) { val d = ds.getJSONObject(k); diffs.add(Diff(d.optString("_difficulty"), d.optString("_beatmapFilename"))) }
            }
        } else {                                                                   // v4
            name = o.optJSONObject("song")?.optString("title", dir.name) ?: dir.name
            val au = o.optJSONObject("audio"); bpm = au?.optDouble("bpm", 120.0)?.toFloat() ?: 120f; audioName = au?.optString("songFilename") ?: ""
            artist = o.optJSONObject("song")?.optString("author", "") ?: ""; coverName = o.optString("coverImageFilename", "")
            val ds = o.optJSONArray("difficultyBeatmaps") ?: JSONArray()
            for (k in 0 until ds.length()) { val d = ds.getJSONObject(k); if (d.optString("characteristic", "Standard") == "Standard") diffs.add(Diff(d.optString("difficulty"), d.optString("beatmapDataFilename"))) }
        }
        if (diffs.isEmpty()) return null
        diffs.sortBy { ORDER.indexOf(it.name.lowercase()).let { i -> if (i < 0) 99 else i } }
        return Song(dir, name, artist, bpm, find(dir, audioName), if (coverName.isEmpty()) null else find(dir, coverName), diffs)
    }

    fun notes(song: Song, diff: Diff): List<Note> {
        val f = find(song.dir, diff.file) ?: return emptyList()
        val o = JSONObject(f.readText())
        val out = ArrayList<Note>()
        val k = 60f / song.bpm
        fun add(beat: Double, x: Int, y: Int, c: Int, d: Int) {
            if (c != 0 && c != 1) return                                           // bomba (tipo 3) e outros: ignorados
            out.add(Note((beat * k).toFloat(), x.coerceIn(0, 3), y.coerceIn(0, 2), c, if (d in 0..7) d else 8))
        }
        when {
            o.has("_notes") -> { val a = o.getJSONArray("_notes"); for (i in 0 until a.length()) { val n = a.getJSONObject(i); add(n.optDouble("_time"), n.optInt("_lineIndex"), n.optInt("_lineLayer"), n.optInt("_type", -1), n.optInt("_cutDirection", 8)) } }
            o.has("colorNotesData") -> {
                val a = o.getJSONArray("colorNotes"); val dt = o.getJSONArray("colorNotesData")
                for (i in 0 until a.length()) { val n = a.getJSONObject(i); val m = dt.optJSONObject(n.optInt("i")) ?: continue; add(n.optDouble("b"), m.optInt("x"), m.optInt("y"), m.optInt("c", -1), m.optInt("d", 8)) }
            }
            o.has("colorNotes") -> { val a = o.getJSONArray("colorNotes"); for (i in 0 until a.length()) { val n = a.getJSONObject(i); add(n.optDouble("b"), n.optInt("x"), n.optInt("y"), n.optInt("c", -1), n.optInt("d", 8)) } }
        }
        out.sortBy { it.t }
        return out
    }

    /** Obstáculos do mapa (formatos v2 "_obstacles" e v3 "obstacles"). Vazio se não houver. */
    fun walls(song: Song, diff: Diff): List<Wall> {
        val f = find(song.dir, diff.file) ?: return emptyList()
        val o = try { JSONObject(f.readText()) } catch (_: Throwable) { return emptyList() }
        val k = 60f / song.bpm
        val out = ArrayList<Wall>()
        try {
            if (o.has("_obstacles")) {
                val a = o.getJSONArray("_obstacles")
                for (i in 0 until a.length()) {
                    val n = a.getJSONObject(i)
                    val type = n.optInt("_type", 0)
                    if (type != 0 && type != 1) continue                                  // tipos exóticos (altura livre) ficam de fora
                    val w = n.optInt("_width", 1).coerceIn(1, 4)
                    val lane = n.optInt("_lineIndex", 0).coerceIn(0, 3)
                    out.add(Wall((n.optDouble("_time") * k).toFloat(), (n.optDouble("_duration", 1.0) * k).toFloat().coerceIn(0.05f, 8f), lane, w, type))
                }
            } else if (o.has("obstacles")) {
                val a = o.getJSONArray("obstacles")
                for (i in 0 until a.length()) {
                    val n = a.getJSONObject(i)
                    val y = n.optInt("y", 0)
                    val h = n.optInt("h", 5)
                    val type = if (y >= 2 || h <= 3) 1 else 0                              // parede alta = abaixar; parede de chão ao teto = desviar
                    val lane = n.optInt("x", 0).coerceIn(0, 3)
                    out.add(Wall((n.optDouble("b") * k).toFloat(), (n.optDouble("d", 1.0) * k).toFloat().coerceIn(0.05f, 8f), lane, n.optInt("w", 1).coerceIn(1, 4), type))
                }
            }
        } catch (_: Throwable) { }
        out.sortBy { it.t }
        return out
    }
}
