package me.weishu.kernelsu.ui.util

import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import com.topjohnwu.superuser.io.SuFile
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.ksuApp
import org.json.JSONArray
import java.io.File


/**
 * @author weishu
 * @date 2023/1/1.
 */
private const val TAG = "KsuCli"

private fun getKsuDaemonPath(): String {
    return ksuApp.applicationInfo.nativeLibraryDir + File.separator + "libksud.so"
}

object KsuCli {
    val SHELL: Shell = createRootShell()
    val GLOBAL_MNT_SHELL: Shell = createRootShell(true)
}

fun getRootShell(globalMnt: Boolean = false): Shell {
    return if (globalMnt) KsuCli.GLOBAL_MNT_SHELL else {
        KsuCli.SHELL
    }
}

fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    return try {
        if (globalMnt) {
            builder.build(getKsuDaemonPath(), "debug", "su", "-g")
        } else {
            builder.build(getKsuDaemonPath(), "debug", "su")
        }
    } catch (e: Throwable) {
        Log.e(TAG, "su failed: ", e)
        builder.build("sh")
    }
}

fun execKsud(args: String): Boolean {
    val shell = getRootShell()
    return ShellUtils.fastCmdResult(shell, "${getKsuDaemonPath()} $args")
}

fun install() {
    val start = SystemClock.elapsedRealtime()
    val result = execKsud("install")
    Log.w(TAG, "install result: $result, cost: ${SystemClock.elapsedRealtime() - start}ms")
}

fun listModules(): String {
    val shell = getRootShell()

    val out =
        shell.newJob().add("${getKsuDaemonPath()} module list").to(ArrayList(), null).exec().out
    return out.joinToString("\n").ifBlank { "[]" }
}

fun getModuleCount(): Int {
    val result = listModules()
    runCatching {
        val array = JSONArray(result)
        return array.length()
    }.getOrElse { return 0 }
}

fun getSuperuserCount(): Int {
    return Natives.allowList.size
}

fun toggleModule(id: String, enable: Boolean): Boolean {
    val cmd = if (enable) {
        "module enable $id"
    } else {
        "module disable $id"
    }
    val result = execKsud(cmd)
    Log.i(TAG, "$cmd result: $result")
    return result
}

fun uninstallModule(id: String): Boolean {
    val cmd = "module uninstall $id"
    val result = execKsud(cmd)
    Log.i(TAG, "uninstall module $id result: $result")
    return result
}

fun installModule(
    uri: Uri,
    onFinish: (Boolean) -> Unit,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): Boolean {
    val resolver = ksuApp.contentResolver
    with(resolver.openInputStream(uri)) {
        val file = File(ksuApp.cacheDir, "module.zip")
        file.outputStream().use { output ->
            this?.copyTo(output)
        }
        val cmd = "module install ${file.absolutePath}"

        val shell = createRootShell()

        val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) {
                onStdout(s ?: "")
            }
        }

        val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) {
                onStderr(s ?: "")
            }
        }

        val result =
            shell.newJob().add("${getKsuDaemonPath()} $cmd").to(stdoutCallback, stderrCallback)
                .exec()
        Log.i("KernelSU", "install module $uri result: $result")

        file.delete()

        onFinish(result.isSuccess)
        return result.isSuccess
    }
}

fun installBoot(
    bootUri: Uri?,
    lkmUri: Uri,
    ota: Boolean,
    onFinish: (Boolean) -> Unit,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): Boolean {
    val resolver = ksuApp.contentResolver

    with(resolver.openInputStream(lkmUri)) {
        val lkmFile = File(ksuApp.cacheDir, "kernelsu.ko")
        lkmFile.outputStream().use { output ->
            this?.copyTo(output)
        }

        if (!lkmFile.exists()) {
            onStdout("- kernelsu.ko not found")
            onFinish(false)
            return false
        }

        val bootFile = bootUri?.let { uri ->
            with(resolver.openInputStream(uri)) {
                val bootFile = File(ksuApp.cacheDir, "boot.img")
                bootFile.outputStream().use { output ->
                    this?.copyTo(output)
                }

                bootFile
            }
        }

        val magiskboot = File(ksuApp.applicationInfo.nativeLibraryDir, "libmagiskboot.so")
        var cmd = "boot-patch -m ${lkmFile.absolutePath} --magiskboot ${magiskboot.absolutePath}"

        cmd += if (bootFile == null) {
            // no boot.img, use -f to force install
            " -f"
        } else {
            " -b ${bootFile.absolutePath}"
        }

        if (ota) {
            cmd += " -u"
        }

        // output dir
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        cmd += " -o $downloadsDir"

        val shell = createRootShell()

        val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) {
                onStdout(s ?: "")
            }
        }

        val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
            override fun onAddElement(s: String?) {
                onStderr(s ?: "")
            }
        }

        val result =
            shell.newJob().add("${getKsuDaemonPath()} $cmd").to(stdoutCallback, stderrCallback)
                .exec()
        Log.i("KernelSU", "install boot $lkmUri result: ${result.isSuccess}")

        lkmFile.delete()
        bootFile?.delete()

        // if boot uri is empty, it is direct install, when success, we should show reboot button
        onFinish(bootUri == null && result.isSuccess)
        return result.isSuccess
    }
}

fun reboot(reason: String = "") {
    val shell = getRootShell()
    if (reason == "recovery") {
        // KEYCODE_POWER = 26, hide incorrect "Factory data reset" message
        ShellUtils.fastCmd(shell, "/system/bin/input keyevent 26")
    }
    ShellUtils.fastCmd(shell, "/system/bin/svc power reboot $reason || /system/bin/reboot $reason")
}

fun rootAvailable(): Boolean {
    val shell = getRootShell()
    return shell.isRoot
}

fun isAbDevice(): Boolean {
    val shell = getRootShell()
    return ShellUtils.fastCmd(shell, "getprop ro.build.ab_update").trim().toBoolean()
}

fun isInitBoot(): Boolean {
    val shell = getRootShell()
    if (shell.isRoot) {
        // if we have root, use /dev/block/by-name/init_boot to check
        val abDevice = isAbDevice()
        val initBootBlock = "/dev/block/by-name/init_boot${if (abDevice) "_a" else ""}"
        val file = SuFile(initBootBlock)
        file.shell = shell
        return file.exists()
    }
    // https://source.android.com/docs/core/architecture/partitions/generic-boot
    return ShellUtils.fastCmd(shell, "getprop ro.product.first_api_level").trim().toInt() >= Build.VERSION_CODES.TIRAMISU
}

fun overlayFsAvailable(): Boolean {
    val shell = getRootShell()
    // check /proc/filesystems
    return ShellUtils.fastCmdResult(shell, "cat /proc/filesystems | grep overlay")
}

fun hasMagisk(): Boolean {
    val shell = getRootShell(true)
    val result = shell.newJob().add("which magisk").exec()
    Log.i(TAG, "has magisk: ${result.isSuccess}")
    return result.isSuccess
}

fun isSepolicyValid(rules: String?): Boolean {
    if (rules == null) {
        return true
    }
    val shell = getRootShell()
    val result =
        shell.newJob().add("${getKsuDaemonPath()} sepolicy check '$rules'").to(ArrayList(), null)
            .exec()
    return result.isSuccess
}

fun getSepolicy(pkg: String): String {
    val shell = getRootShell()
    val result =
        shell.newJob().add("${getKsuDaemonPath()} profile get-sepolicy $pkg").to(ArrayList(), null)
            .exec()
    Log.i(TAG, "code: ${result.code}, out: ${result.out}, err: ${result.err}")
    return result.out.joinToString("\n")
}

fun setSepolicy(pkg: String, rules: String): Boolean {
    val shell = getRootShell()
    val result =
        shell.newJob().add("${getKsuDaemonPath()} profile set-sepolicy $pkg '$rules'")
            .to(ArrayList(), null).exec()
    Log.i(TAG, "set sepolicy result: ${result.code}")
    return result.isSuccess
}

fun listAppProfileTemplates(): List<String> {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile list-templates").to(ArrayList(), null)
        .exec().out
}

fun getAppProfileTemplate(id: String): String {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile get-template '${id}'")
        .to(ArrayList(), null)
        .exec().out.joinToString("\n")
}

fun setAppProfileTemplate(id: String, template: String): Boolean {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile set-template '${id}' '${template}'")
        .to(ArrayList(), null)
        .exec().isSuccess
}

fun deleteAppProfileTemplate(id: String): Boolean {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile delete-template '${id}'")
        .to(ArrayList(), null)
        .exec().isSuccess
}

fun forceStopApp(packageName: String) {
    val shell = getRootShell()
    val result = shell.newJob().add("am force-stop $packageName").exec()
    Log.i(TAG, "force stop $packageName result: $result")
}

fun launchApp(packageName: String) {

    val shell = getRootShell()
    val result =
        shell.newJob().add("monkey -p $packageName -c android.intent.category.LAUNCHER 1").exec()
    Log.i(TAG, "launch $packageName result: $result")
}

fun restartApp(packageName: String) {
    forceStopApp(packageName)
    launchApp(packageName)
}