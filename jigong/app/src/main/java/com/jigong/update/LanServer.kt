package com.jigong.update

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 把本机变成一台「更新源」，让同一 WiFi / 热点下的另一台手机从我这装。
 *
 * 场景：你手机上已经是 1.8，对方还是 1.7。你打开这个开关，对方手机上点
 * 「自动查找」，会同时找到电脑和你的手机，然后装版本最高的那个 —— 不需要电脑，
 * 也不需要把 APK 塞进微信（微信会把 .apk 改名成 .apk.1，对方要改回来才能装）。
 *
 * 只做两件事，不引 Ktor / NanoHTTPD —— 就两个 GET，为它背 2MB 依赖不值当：
 *   GET /version.json  →  {"versionCode":8,"versionName":"1.8","apk":"jigong.apk",...}
 *   GET /app.apk       →  本机安装包的原始字节
 *
 * 服务器只活在这个页面打开的时候（关掉面板就停）。这是刻意的：
 * 国产 ROM 对后台网络管控很严（社区实测 ColorOS 16 退到后台就断连，要手动开
 * 「完全允许后台行为」）。跑在用户眼前的这一两分钟里，这些管控都不存在。
 *
 * ⚠️ 将来要注意：Android 17 会强制 ACCESS_LOCAL_NETWORK（NEARBY_DEVICES 权限组），
 * 到时候本类里的 TCP 监听和 UDP 广播都会被系统拦掉，需要在 Manifest 申明该权限
 * 并在运行时请求。Android 16 上这个限制目前还是 opt-in（adb am compat enable
 * RESTRICT_LOCAL_NETWORK 才会生效），所以现在能正常跑。
 */
object LanServer {

    const val PORT = 8767

    /** 和电脑端 lan-update-server.py 用同一套自动发现协议，两边可以同时被搜到 */
    private const val DISCOVER_PORT = 8766
    private const val PROBE = "JIGONG1"
    private const val REPLY_PREFIX = "JIGONG:"

    @Volatile
    var running = false
        private set

    /** 已经完整发出去几份，给界面显示。中途断开的连接不算 */
    @Volatile
    var served = 0
        private set

    private var tcp: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var apk: File? = null
    private var meta: String = ""

    /**
     * 启动。会先把本机 APK 导出到缓存并算好 SHA-256，所以**必须在 IO 线程调用**
     * （18MB 复制 + 哈希，100~300ms）。
     *
     * 返回失败通常是 8767 被别的进程占了 —— 极少见，但要让用户看到原因，
     * 而不是点了开关没反应。
     */
    fun start(ctx: Context): Result<Unit> {
        if (running) return Result.success(Unit)

        return runCatching {
            // 用独立目录，别碰分享用的那份：两者共用目录会互相删掉
            val f = ShareApk.export(ctx, ShareApk.SERVE_DIR)
            val code = ShareApk.versionCodeOf(ctx)
            val name = ShareApk.versionNameOf(ctx)
            meta = JSONObject().apply {
                put("versionCode", code)
                put("versionName", name)
                put("apk", "jigong.apk")
                put("sha256", ShareApk.sha256(f))
                put("size", f.length())
            }.toString()
            apk = f
            served = 0

            // 绑 0.0.0.0：WiFi 和热点共用同一个监听，连哪个网卡都能连进来
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(PORT))
            tcp = server
            running = true

            thread(name = "jigong-lan-tcp", isDaemon = true) { acceptLoop(server) }
            // UDP 应答失败了不影响主流程（顶多是不能自动发现，还能手输 IP），
            // 常见原因是 8766 已经被别的程序占着
            runCatching { startDiscover() }
        }
    }

    fun stop() {
        running = false
        runCatching { tcp?.close() }
        runCatching { udp?.close() }
        tcp = null
        udp = null
        // 停服务就把导出那份删掉，别留 18MB
        runCatching { apk?.delete() }
        apk = null
    }

    // ------------------------------------------------------------ TCP

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break   // 被 stop() 关掉，正常退出
            }
            // 串行处理：一台手机传一台，没必要并发。并发反而要处理多个读 APK 的竞争
            try {
                handle(socket)
            } catch (e: Exception) {
                android.util.Log.w("jigong", "局域网请求失败：${e.message}")
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 8000
        socket.tcpNoDelay = true

        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        // 必须把请求头读到空行，否则我们直接关 socket 会让对方收到 RST
        while (true) {
            val h = reader.readLine() ?: break
            if (h.isEmpty()) break
        }

        val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?').orEmpty()
        when (path) {
            "/version.json" -> {
                val body = meta.toByteArray(Charsets.UTF_8)
                head(socket, "200 OK", "application/json; charset=utf-8", body.size.toLong())
                socket.getOutputStream().apply {
                    write(body)
                    flush()
                }
            }

            "/app.apk", "/jigong.apk" -> {
                val f = apk ?: throw IllegalStateException("安装包还没准备好")
                head(socket, "200 OK", "application/vnd.android.package-archive", f.length())
                // 分块写，不要一次读进内存：18MB 的 byte[] 在低端机上可能直接 OOM
                val out = socket.getOutputStream()
                f.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
                out.flush()
                served++
            }

            else -> {
                val body = "not found".toByteArray()
                head(socket, "404 Not Found", "text/plain; charset=utf-8", body.size.toLong())
                socket.getOutputStream().apply {
                    write(body)
                    flush()
                }
            }
        }
    }

    private fun head(socket: Socket, status: String, type: String, length: Long) {
        val h = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: $length\r\n")
            append("Cache-Control: no-store\r\n")   // 别让对端拿缓存里的旧包
            append("Connection: close\r\n")
            append("\r\n")
        }
        socket.getOutputStream().apply {
            write(h.toByteArray(Charsets.ISO_8859_1))
            flush()
        }
    }

    // ------------------------------------------------------------ UDP 自动发现

    private fun startDiscover() {
        val sock = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(DISCOVER_PORT))
        }
        udp = sock

        thread(name = "jigong-lan-udp", isDaemon = true) {
            val buf = ByteArray(64)
            while (running) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(packet)
                } catch (_: Exception) {
                    break
                }
                val text = String(packet.data, 0, packet.length).trim()
                if (text != PROBE) continue

                // 每次现算地址：用户可能在开服务之后才连上 WiFi / 打开热点。
                // 手机可能同时有 WiFi 和热点两个地址，全都回一遍，让对端自己试哪个通
                rawLanIps().forEach { (_, ip) ->
                    val msg = "$REPLY_PREFIX$ip:$PORT".toByteArray()
                    runCatching { sock.send(DatagramPacket(msg, msg.size, packet.address, packet.port)) }
                }
            }
        }
    }

    // ------------------------------------------------------------ 本机地址

    /**
     * 只认 WiFi / 热点的网卡。蜂窝必须排除。
     *
     * 实测这台 vivo 同时挂着 wlan0(192.168.1.10)、wlan1(192.168.1.11，双 WiFi 加速)
     * 和 rmnet_data2(10.0.0.2，蜂窝内网)。把蜂窝地址也报给对端，对方一定会去试，
     * 然后白等 2.5 秒超时 —— 纯粹是噪音。所以按接口名前缀过滤。
     *
     * （文中地址为示意：192.168.1.x 是家用 WiFi 网段，10.0.0.x 是运营商内网。
     *  实测时的真实网段与之不同 —— 写死的真实地址相当于把自己的网络拓扑公开出去。）
     *
     * UDP 应答用这个（不区分主次，全回一遍让对端自己试哪条通）。
     */
    private val LAN_IF_PREFIX = listOf("wlan", "ap", "swlan", "softap", "p2p", "eth")

    fun rawLanIps(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .filter { ni -> LAN_IF_PREFIX.any { p -> ni.name.lowercase().startsWith(p) } }
            .flatMap { ni ->
                ni.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .map { ni.name to (it.hostAddress ?: "") }
            }
            .filter { it.second.isNotEmpty() }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }.getOrDefault(emptyList())

    /** 一个地址，以及它在这台手机上扮演的角色 */
    data class LanAddr(val ifName: String, val ip: String, val role: Role)

    /**
     * - [WIFI]    当前联网的那张 WiFi 卡。同一 WiFi 下的对方用这个。
     * - [HOTSPOT] 本机开的热点，和 [WIFI] 不在同一个网段。连我热点的对方用这个。
     * - [OTHER]   其余能连的地址（双 WiFi 加速的另一条、USB 网卡等），连不上主地址时可以试。
     */
    enum class Role { WIFI, HOTSPOT, OTHER }

    /**
     * 给界面用的地址列表，带角色。
     *
     * 为什么必须分角色：实测开热点后这台手机变成 wlan0(192.168.1.10，上游路由器)
     * + wlan2(192.168.43.x，热点)。对方连我热点时只能访问热点那个，而它按字典序
     * 恰好排在后面 —— 不标注就会把对方用不上的地址当主地址给出去。
     *
     * ⚠️ 光用「不是活动网络就是热点」判断是错的：这台 vivo 开了「双 WiFi 加速」，
     * 同时挂 wlan0(192.168.1.10) 和 wlan1(192.168.1.11)，两张卡接的是**同一台路由器**，
     * 都会被判成"非活动网络"。所以还要看网段：和活动网络同一个 /24 的，只是同一条局域网
     * 的另一条链路，不是热点。（Android 开热点时会主动避开上游已占用的网段，
     * 所以「同 /24 即同一局域网」这条在热点场景下不会误判。）
     */
    fun localIps(ctx: Context): List<LanAddr> {
        val sta = staInterface(ctx)
        val raw = rawLanIps()
        val staIp = raw.firstOrNull { it.first == sta }?.second
        val staSubnet = subnet24(staIp)

        return raw.map { (name, ip) ->
            val lname = name.lowercase()
            val isSta = sta != null && name == sta
            // 同一台路由器给的第二个地址（双 WiFi 加速）
            val sameLan = staSubnet != null && subnet24(ip) == staSubnet
            // 接口名本身就是热点（ap0 / softap0 / swlan0），有些机型叫 wlan2 看不出来，靠网段补
            val hotspotName = lname.startsWith("ap") || lname.startsWith("softap") || lname.startsWith("swlan")

            val role = when {
                isSta -> Role.WIFI
                // 同一网段又不是热点网卡 → 只是同一条局域网的第二条链路
                sameLan && !hotspotName -> Role.OTHER
                else -> Role.HOTSPOT
            }
            LanAddr(name, ip, role)
        }
            // 主地址排最前：界面和二维码都取第一个，顺序就是优先级
            .sortedWith(compareBy({ it.role.ordinal }, { it.ifName.lowercase() }))
    }

    /** 取前 3 段（/24）。手机上的局域网几乎都是 /24，够用了 */
    private fun subnet24(ip: String?): String? {
        val p = ip?.split('.') ?: return null
        return if (p.size == 4) p.take(3).joinToString(".") else null
    }

    /** 当前活动网络（有默认路由的那张卡）的接口名，拿不到就返回 null，退化成不区分 */
    private fun staInterface(ctx: Context): String? = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return@runCatching null
        cm.getLinkProperties(net)?.interfaceName
    }.getOrNull()
}
