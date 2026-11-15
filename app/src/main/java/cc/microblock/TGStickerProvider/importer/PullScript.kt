package cc.microblock.TGStickerProvider.importer

/**
 * The shell script that snapshots a client into /sdcard/TGSP.
 *
 * Same text is shipped as tgsp_pull.sh for manual use in MT/Termux. When Sui/Shizuku is running as
 * a root service the app runs this through Shizuku.newProcess instead, i.e. the commands execute in
 * Sui's own root process - not in this app's mount namespace, which is the reason an in-app `su`
 * could never see /data/user/0/<client>.
 */
object PullScript {

    fun build(pkg: String): String = """
        OUT=/sdcard/TGSP
        DATA=/data/user/0/$pkg
        CACHE=/storage/emulated/0/Android/data/$pkg/cache
        echo "root uid: ${'$'}(id -u)"
        [ -d "${'$'}DATA" ] || { echo "NO_DATA_DIR ${'$'}DATA"; exit 1; }
        mkdir -p "${'$'}OUT/db" "${'$'}OUT/cache"
        rm -f "${'$'}OUT/db"/*.db "${'$'}OUT/db"/*.db-wal "${'$'}OUT/db"/*.db-shm 2>/dev/null
        copy_one() {
          src="${'$'}1"; name="${'$'}2"; ok=0
          for try in 1 2 3; do
            [ -f "${'$'}src-wal" ] && cp -f "${'$'}src-wal" "${'$'}OUT/db/${'$'}name.db-wal" 2>/dev/null
            cp -f "${'$'}src" "${'$'}OUT/db/${'$'}name.db" 2>/dev/null && ok=1
            [ ${'$'}ok -eq 1 ] && break
            sleep 1
          done
          [ ${'$'}ok -eq 1 ] && echo "DB ${'$'}name.db" || echo "DB_FAIL ${'$'}name"
        }
        [ -f "${'$'}DATA/files/cache4.db" ] && copy_one "${'$'}DATA/files/cache4.db" main
        for a in "${'$'}DATA/files"/account*; do
          [ -f "${'$'}a/cache4.db" ] || continue
          copy_one "${'$'}a/cache4.db" "${'$'}(basename "${'$'}a")"
        done
        if [ -d "${'$'}CACHE" ]; then
          find "${'$'}CACHE" -maxdepth 1 -type f \( -name '*.webp' -o -name '*.webm' \) -exec cp -f {} "${'$'}OUT/cache/" \; 2>/dev/null
          echo "CACHE_FILES ${'$'}(ls -1 "${'$'}OUT/cache" | wc -l)"
        else
          echo "NO_CACHE_DIR"
        fi
        echo "PULL_DONE"
    """.trimIndent()
}
