package cn.rain.zagent.linux

import android.content.Context
import android.system.Os
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.StringWriter
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**

LinuxShellBridge — 用 proot + 自带 Termux bootstrap 提供完整 Linux 命令环境。

机制：

首次调用时把 assets/linux/ 下的 bootstrap 解压到 filesDir/linux/，并恢复可执行位、重建 SYMLINKS。

可执行 ELF（proot / loader / loader32）放在 jniLibs 下，运行时一律从 nativeLibraryDir

只读路径取用（文件名带 .so 前缀 lib 以被 Gradle 打包），不从可写目录执行。

每条命令通过 ProcessBuilder 启动 proot，由 proot 在假 rootfs 内执行 bash -lc。

返回值是 JSON 字符串，方便 Python 侧直接 json.loads。
*/
object LinuxShellBridge {
    private const val TAG = "LinuxShell"
    private const val MARKER = ".linux-boot-v2"

    private var ctxRef: WeakReference<Context>? = null

    @Volatile private var ready = false
    @Volatile private var booting = false
    @Volatile private var lastError: String = ""

    data class ExecResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean
    )

    @JvmStatic
    fun init(context: Context) {
        ctxRef = WeakReference(context.applicationContext)
    }

    /** nativeLibraryDir：只读执行 ELF 的位置（libproot.so / libproot_loader.so） */
    private fun libDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    private fun requireLib(ctx: Context, name: String): File {
        val f = File(libDir(ctx), name)
        if (!f.exists()) throw IllegalStateException("native lib 缺失: " + name)
        return f
    }

    @JvmStatic
    fun isReady(): Boolean = ready

    @JvmStatic
    fun getLastError(): String = lastError

    @JvmStatic
    fun statusJson(): String {
        val o = JSONObject()
        o.put("ready", ready)
        o.put("booting", booting)
        o.put("err", lastError)
        val ctx = ctxRef?.get()
        if (ctx != null) {
            val rootfs = File(ctx.filesDir, "linux/usr")
            o.put("rootfs_exists", rootfs.exists())
            o.put("bash_exists", File(rootfs, "bin/bash").exists())
            val ld = libDir(ctx)
            o.put("proot_in_nativelib", File(ld, "libproot.so").exists())
            o.put("loader_in_nativelib", File(ld, "libproot_loader.so").exists())
        }
        return o.toString()
    }

    @JvmStatic
    @JvmOverloads
    fun execJson(cmd: String, timeoutSec: Int = 60): String {
        val r = exec(cmd, timeoutSec)
        val o = JSONObject()
        o.put("ok", r.code == 0 && !r.timedOut)
        o.put("code", r.code)
        o.put("stdout", r.stdout)
        o.put("stderr", r.stderr)
        o.put("timeout", r.timedOut)
        return o.toString()
    }

    @JvmStatic
    @JvmOverloads
    fun exec(cmd: String, timeoutSec: Int = 60): ExecResult {
        val ctx = ctxRef?.get()
        ?: return ExecResult(-1, "", "LinuxShellBridge 未初始化（先调用 init）", false)
        if (!ready && !ensureBootstrapped(ctx)) {
            return ExecResult(-1, "", "bootstrap 失败: " + lastError, false)
        }
        return runProot(ctx, cmd, timeoutSec)
    }

    @Synchronized
    @JvmStatic
    fun ensureBootstrapped(context: Context): Boolean {
        if (ready) return true
        if (booting) {
            val t0 = System.currentTimeMillis()
            while (booting && System.currentTimeMillis() - t0 < 180_000L) {
                try { Thread.sleep(200L) } catch (_: InterruptedException) {}
            }
            return ready
        }
        booting = true
        try {
            ctxRef = WeakReference(context.applicationContext)
            val ctx = context.applicationContext
            val binDir = File(ctx.filesDir, "linux")
            val rootfs = File(binDir, "usr")
            binDir.mkdirs()

            // 主程序 / loader 必须来自 nativeLibraryDir（只读），不允许从可写目录执行 ELF
            requireLib(ctx, "libproot.so")
            requireLib(ctx, "libproot_loader.so")

            val marker = File(binDir, MARKER)
            if (marker.exists() && File(rootfs, "bin/bash").exists()) {
                ready = true
                return true
            }

            Log.i(TAG, "bootstrap 开始")
            copyAsset(ctx, "linux/libandroid-shmem.so", File(binDir, "libandroid-shmem.so"), false)
            copyAsset(ctx, "linux/libtalloc.so.2", File(binDir, "libtalloc.so.2"), false)
            copyAsset(ctx, "linux/ld.config.txt", File(binDir, "ld.config.txt"), false)
            copyAsset(ctx, "linux/exec-manifest.txt", File(binDir, "exec-manifest.txt"), false)
            Log.i(TAG, "辅助文件复制完成")

            extractBootstrap(ctx, "linux/bootstrap-aarch64.zip", rootfs)
            Log.i(TAG, "rootfs 解压完成")

            File(rootfs, "root").mkdirs()
            File(rootfs, "tmp").mkdirs()
            File(rootfs, "dev").mkdirs()
            File(rootfs, "proc").mkdirs()
            File(rootfs, "sys").mkdirs()

            if (!File(rootfs, "bin/bash").exists()) {
                lastError = "解压后仍缺 bin/bash"
                Log.e(TAG, lastError)
                return false
            }

            marker.writeText(System.currentTimeMillis().toString())
            ready = true
            lastError = ""
            Log.i(TAG, "bootstrap 完成")
            return true
        } catch (e: Throwable) {
            lastError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "bootstrap 异常: " + lastError, e)
            return false
        } finally {
            booting = false
        }
    }

    private fun runProot(ctx: Context, cmd: String, timeoutSec: Int): ExecResult {
        val binDir = File(ctx.filesDir, "linux")
        val rootfs = File(binDir, "usr")
        val ld = libDir(ctx)
        val proot = File(ld, "libproot.so")
        if (!proot.exists()) return ExecResult(-1, "", "libproot.so 缺失（nativeLibraryDir）", false)
        val loader = File(ld, "libproot_loader.so")
        if (!loader.exists()) return ExecResult(-1, "", "libproot_loader.so 缺失（nativeLibraryDir）", false)
        val tmpDir = File(ctx.cacheDir, "proot-tmp")
        tmpDir.mkdirs()

        val args = arrayListOf(
        proot.absolutePath,
        "--link2symlink",
        "-0",
        "-r", rootfs.absolutePath,
        "-b", "/dev",
        "-b", "/proc",
        "-b", "/sys",
        "-b", "/sdcard",
        "-b", "/storage",
        "-b", ctx.filesDir.absolutePath,
        "-w", "/root",
        "/usr/bin/env", "-i",
        "HOME=/root",
        "PATH=/usr/bin:/usr/sbin:/bin:/sbin",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "/usr/bin/bash", "-lc", cmd
        )

        val pb = ProcessBuilder(args)
        pb.directory(File(rootfs, "root"))
        val env = pb.environment()
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        env["PROOT_LOADER"] = loader.absolutePath
        val loader32 = File(ld, "libproot_loader32.so")
        if (loader32.exists()) env["PROOT_LOADER_32"] = loader32.absolutePath
        env["LD_LIBRARY_PATH"] = binDir.absolutePath + ":" + ld.absolutePath

        return try {
            val proc = pb.start()
            val outW = StringWriter()
            val errW = StringWriter()
            val t1 = Thread { try { proc.inputStream.bufferedReader().use { it.copyTo(outW) } } catch (ex: Throwable) {} }
            val t2 = Thread { try { proc.errorStream.bufferedReader().use { it.copyTo(errW) } } catch (ex: Throwable) {} }
            t1.start(); t2.start()

            val finished = proc.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                killTree(proc)
                t1.join(1500L); t2.join(1500L)
                return ExecResult(-1, outW.toString(), errW.toString(), true)
            }
            t1.join(2000L); t2.join(2000L)
            ExecResult(proc.exitValue(), outW.toString(), errW.toString(), false)
        } catch (e: Throwable) {
            ExecResult(-1, "", "执行异常: " + (e.message ?: ""), false)
        }
    }

    private fun descendants(root: Int): List<Int> {
        val children = HashMap<Int, MutableList<Int>>()
        File("/proc").list()?.forEach { name ->
            val pid = name.toIntOrNull() ?: return@forEach
            try {
                val stat = File("/proc/" + name + "/stat").readText()
                val close = stat.lastIndexOf(')')
                if (close < 0) return@forEach
                val rest = stat.substring(close + 1).trim().split(" ")
                val ppid = rest.getOrNull(1)?.toIntOrNull() ?: return@forEach
                children.getOrPut(ppid) { mutableListOf() }.add(pid)
            } catch (e: Throwable) {
            }
        }
        val out = mutableListOf<Int>()
        val queue = ArrayList<Int>()
        queue.add(root)
        var head = 0
        var guard = 0
        while (head < queue.size && guard < 5000) {
            guard++
            val cur = queue[head]
            head++
            children[cur]?.forEach { c ->
                out.add(c)
                queue.add(c)
            }
        }
        return out
    }

    // SIGTERM 让 proot 自行清理 shm 与临时文件；宽限 1.5s 后按后代逆序 SIGKILL。
    private fun killTree(proc: Process) {
        // If pid is unavailable, fall back to scanning descendants of this process.
        // The agent loop is single-threaded, so only one shell runs at a time.
        var root = processPid(proc)
        val fromSelf = root <= 0
        if (fromSelf) root = android.os.Process.myPid()
        if (!fromSelf) {
            try { proc.destroy() } catch (e: Throwable) {}
            val deadline = System.currentTimeMillis() + 1500L
            while (System.currentTimeMillis() < deadline) {
                if (!proc.isAlive) break
                try { Thread.sleep(50L) } catch (e: InterruptedException) {}
            }
        }
        descendants(root).asReversed().forEach { pid ->
            try { Os.kill(pid, 9) } catch (e: Throwable) {}
        }
        try { proc.destroyForcibly() } catch (e: Throwable) {}
        descendants(root).asReversed().forEach { pid ->
            try { Os.kill(pid, 9) } catch (e: Throwable) {}
        }
    }

    private fun processPid(p: Process): Int {
        // Process.pid() is not visible in every Android SDK; reflect, then parse toString.
        try {
            val m = p.javaClass.getMethod("pid")
            val v = m.invoke(p)
            if (v is Int) return v
            if (v is Long) return v.toInt()
        } catch (e: Throwable) {}
        try {
            val s = p.toString()
            val key = "pid="
            var i = s.indexOf(key)
            if (i >= 0) {
                i += key.length
                var n = 0
                var any = false
                while (i < s.length) {
                    val c = s[i]
                    if (c < '0' || c > '9') break
                    n = n * 10 + (c - '0')
                    any = true
                    i++
                }
                if (any && n > 0) return n
            }
        } catch (e: Throwable) {}
        return -1
    }


    private fun copyAsset(ctx: Context, assetPath: String, dest: File, executable: Boolean) {
        dest.parentFile?.mkdirs()
        ctx.assets.open(assetPath).use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        }
        if (executable) dest.setExecutable(true, false)
    }

    private fun extractBootstrap(ctx: Context, assetPath: String, destDir: File) {
        destDir.mkdirs()
        ctx.assets.open(assetPath).use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.isNotEmpty() && !name.startsWith("..") && !name.startsWith("/")) {
                        val f = File(destDir, name)
                        if (entry.isDirectory) {
                            f.mkdirs()
                        } else {
                            f.parentFile?.mkdirs()
                            try {
                                f.outputStream().use { out -> zip.copyTo(out) }
                            } catch (e: Throwable) {
                                Log.w(TAG, "写入失败 " + name + ": " + (e.message ?: ""))
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        destDir.walkTopDown().forEach { f ->
            if (f.isFile) {
                val rel = f.relativeTo(destDir).path
                if (rel.startsWith("bin/") || rel.startsWith("lib/") ||
                rel.startsWith("usr/bin/") || rel.startsWith("usr/lib/") ||
                rel.startsWith("usr/libexec/")) {
                    f.setExecutable(true, false)
                }
            }
        }

        val sl = File(destDir, "SYMLINKS.txt")
        if (sl.exists()) {
            var ok = 0
            var fail = 0
            try {
                sl.forEachLine { line ->
                    val idx = line.indexOf('←')
                    if (idx > 0) {
                        val target = line.substring(0, idx)
                        val link = line.substring(idx + 1)
                        if (link.isNotBlank() && !link.startsWith("/") && !link.contains("..")) {
                            val linkFile = File(destDir, link)
                            linkFile.parentFile?.mkdirs()
                            try {
                                if (linkFile.exists()) linkFile.delete()
                                Os.symlink(target, linkFile.absolutePath)
                                ok++
                            } catch (_: Throwable) {
                                fail++
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "处理 SYMLINKS.txt 失败: " + (e.message ?: ""))
            }
            Log.i(TAG, "symlinks ok=" + ok + " fail=" + fail)
            sl.delete()
        }
    }
}