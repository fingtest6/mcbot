import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.auth.SessionService;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.Flag;
import org.geysermc.mcprotocollib.network.event.session.*;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.text.SimpleDateFormat;
import java.util.Date;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class McBot {
    private static final Flag<SessionService> SESSION_SERVICE_KEY = new Flag<>("session_service", null);
    private static final String YGGDRASIL_API = "https://littleskin.cn/api/yggdrasil";
    private static CountDownLatch connectedLatch = new CountDownLatch(1);

    private static boolean clientInfoSent = false;
    private static boolean brandResponded = false;
    private static boolean inGame = false;
    private static boolean keepAliveStarted = false;
    private static boolean isDead = false;
    private static double posX = 0.0;
    private static double posY = 70.0;
    private static double posZ = 0.0;
    private static float yaw = 0.0f;
    private static float pitch = 0.0f;
    private static int entityId = -1;

    private static int reconnectCount = 0;
    private static final int MAX_RECONNECT = 10;
    private static final AtomicBoolean keepAliveRunning = new AtomicBoolean(false);
    private static final AtomicInteger posSendCount = new AtomicInteger(0);
    private static final AtomicInteger heartbeatCount = new AtomicInteger(0);

    private static String serverHost;
    private static int serverPort;
    private static long gameJoinTime = 0;
    private static String playerName = "";
    private static volatile boolean running = true;

    private static final Map<UUID, String> playerNames = new HashMap<>();
    private static final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    public static void main(String[] args) {
        serverHost = BotConfig.get("mc.host", "localhost");
        serverPort = BotConfig.getInt("mc.port", 25565);
        String username = BotConfig.get("mc.username", "stzr@foxmail.com");
        String password = BotConfig.get("mc.password", "");
        boolean offlineMode = BotConfig.get("mc.offline", "false").equals("true");

        if (!offlineMode && password.isEmpty()) {
            System.err.println("[" + time() + "] ❌ 请在 config.properties 中设置密码！");
            return;
        }

        final ClientSession[] sessionHolder = new ClientSession[1];
        final MinecraftProtocol[] protocolHolder = new MinecraftProtocol[1];

        try {
            printBanner();

            log("📡", "正在初始化...");
            log("📡", "  目标: " + serverHost + ":" + serverPort);
            log("📡", "  账号: " + username);
            log("📡", "  模式: " + (offlineMode ? "离线" : "Yggdrasil"));

            log("🔐", "正在进行身份验证...");
            if (offlineMode) {
                protocolHolder[0] = new MinecraftProtocol(username);
                playerName = username;
            } else {
                YggdrasilAuthResult authResult = authenticate(username, password);
                if (authResult == null) { log("❌", "认证失败！"); return; }
                protocolHolder[0] = new MinecraftProtocol(authResult.profile, authResult.accessToken);
                playerName = authResult.profile.getName();
            }
            log("✅", "认证成功！欢迎 " + playerName);
            protocolHolder[0].setUseDefaultListeners(true);
            resetState();

            log("🔧", "正在创建网络会话...");
            sessionHolder[0] = createSession(protocolHolder[0], sessionHolder, protocolHolder);

            log("🚀", "正在连接服务器...");
            Thread connectThread = new Thread(() -> {
                try { sessionHolder[0].connect(); } catch (Exception e) { connectedLatch.countDown(); }
            });
            connectThread.start();

            if (!connectedLatch.await(30, TimeUnit.SECONDS)) {
                log("❌", "连接超时！"); System.exit(1);
            }
            log("✅", "TCP 连接已建立");
            log("📡", "等待登录和配置...");

            // 控制台输入线程
            Thread consoleThread = new Thread(() -> {
                Scanner scanner = new Scanner(System.in);
                log("💡", "控制台已就绪！输入 'help' 查看命令");

                while (running) {
                    try {
                        if (scanner.hasNextLine()) {
                            String input = scanner.nextLine().trim();
                            if (input.isEmpty()) continue;

                            ClientSession currentSession = sessionHolder[0];
                            if (currentSession == null || !currentSession.isConnected()) {
                                log("⚠", "未连接到服务器"); continue;
                            }

                            switch (input.toLowerCase()) {
                                case "help": showHelp(); break;
                                case "info": showInfo(); break;
                                case "quit": case "exit":
                                    log("👋", "正在退出...");
                                    running = false;
                                    currentSession.disconnect("用户退出");
                                    System.exit(0);
                                    break;
                                default:
                                    if (input.startsWith("/")) {
                                        sendCommand(currentSession, input.substring(1));
                                    } else {
                                        sendChatMessage(currentSession, input);
                                    }
                            }
                        }
                    } catch (Exception e) {
                        log("❌", "控制台错误: " + e.getMessage());
                    }
                }
                scanner.close();
            }, "ConsoleThread");
            consoleThread.setDaemon(true);
            consoleThread.start();

            while (running) {
                if (sessionHolder[0].isConnected()) {
                    Thread.sleep(1000);
                } else if (reconnectCount > 0 && reconnectCount <= MAX_RECONNECT) {
                    Thread.sleep(5000);
                } else {
                    break;
                }
            }

        } catch (Exception e) {
            log("❌", "程序异常: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void showHelp() {
        System.out.println();
        System.out.println("  help / info / quit");
        System.out.println("  <消息> 发送聊天");
        System.out.println("  /<命令> 执行命令");
        System.out.println();
    }

    private static void showInfo() {
        long elapsed = gameJoinTime > 0 ? (System.currentTimeMillis() - gameJoinTime) / 1000 : 0;
        System.out.println();
        System.out.println("  目标: " + serverHost + ":" + serverPort);
        System.out.println("  角色: " + playerName + "  实体: " + entityId);
        System.out.println("  位置: (" + String.format("%.0f", posX) + "," + String.format("%.0f", posY) + "," + String.format("%.0f", posZ) + ")");
        System.out.println("  在线: " + formatTime(elapsed) + "  心跳: " + heartbeatCount.get());
        System.out.println("  状态: " + (isDead ? "已死亡" : inGame ? "游戏中" : "配置中"));
        System.out.println();
    }

    private static void sendChatMessage(Session session, String message) {
        try {
            Class<?> chatClass = Class.forName(
                    "org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket");
            for (java.lang.reflect.Constructor<?> c : chatClass.getConstructors()) {
                try {
                    Class<?>[] pts = c.getParameterTypes();
                    Object[] args = new Object[pts.length];
                    for (int i = 0; i < pts.length; i++) {
                        if (pts[i] == String.class) args[i] = message;
                        else if (pts[i] == int.class) args[i] = 0;
                        else if (pts[i] == long.class) args[i] = System.currentTimeMillis();
                        else if (pts[i] == boolean.class) args[i] = false;
                        else args[i] = null;
                    }
                    session.send((Packet) c.newInstance(args));
                    log("💬", "已发送: " + message);
                    return;
                } catch (Exception e) { continue; }
            }
        } catch (Exception e) {}
    }

    private static void sendCommand(Session session, String command) {
        try {
            Class<?> cmdClass = Class.forName(
                    "org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket");
            for (java.lang.reflect.Constructor<?> c : cmdClass.getConstructors()) {
                try {
                    Class<?>[] pts = c.getParameterTypes();
                    Object[] args = new Object[pts.length];
                    for (int i = 0; i < pts.length; i++) {
                        if (pts[i] == String.class) args[i] = command;
                        else if (pts[i] == int.class) args[i] = 0;
                        else if (pts[i] == long.class) args[i] = System.currentTimeMillis();
                        else if (pts[i] == boolean.class) args[i] = false;
                        else args[i] = null;
                    }
                    session.send((Packet) c.newInstance(args));
                    log("⚡", "已执行: /" + command);
                    return;
                } catch (Exception e) { continue; }
            }
            sendChatMessage(session, "/" + command);
        } catch (Exception e) {
            sendChatMessage(session, "/" + command);
        }
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════╗");
        System.out.println("  ║        MCBot - Minecraft 机器人      ║");
        System.out.println("  ║        基于 MCPL 协议库              ║");
        System.out.println("  ╚══════════════════════════════════════╝");
        System.out.println();
    }

    private static String time() { return timeFormat.format(new Date()); }

    private static void log(String emoji, String msg) {
        System.out.println("[" + time() + "] " + emoji + " " + msg);
    }

    private static ClientSession createSession(MinecraftProtocol protocol,
                                               ClientSession[] sessionHolder, MinecraftProtocol[] protocolHolder) {

        ClientNetworkSessionFactory factory = ClientNetworkSessionFactory.factory()
                .setAddress(serverHost, serverPort).setProtocol(protocol);
        ClientSession session = factory.create();
        session.setFlag(SESSION_SERVICE_KEY, new SessionService());

        session.addListener(new SessionAdapter() {
            @Override
            public void connected(ConnectedEvent event) { connectedLatch.countDown(); }

            @Override
            public void disconnected(DisconnectedEvent event) {
                keepAliveRunning.set(false);
                long elapsed = gameJoinTime > 0 ? (System.currentTimeMillis() - gameJoinTime) / 1000 : 0;

                if (reconnectCount < MAX_RECONNECT) {
                    reconnectCount++;
                    log("⚠", "断开 [第" + reconnectCount + "次] 在线:" + formatTime(elapsed));
                    log("🔄", "5秒后重连...");
                    new Thread(() -> {
                        try {
                            Thread.sleep(5000);
                            resetState();
                            ClientSession newSession = createSession(protocolHolder[0], sessionHolder, protocolHolder);
                            sessionHolder[0] = newSession;
                            connectedLatch = new CountDownLatch(1);
                            newSession.connect();
                            if (!connectedLatch.await(30, TimeUnit.SECONDS)) System.exit(1);
                            log("✅", "重连成功！");
                        } catch (Exception e) { System.exit(1); }
                    }).start();
                    return;
                }
                running = false;
                log("❌", "达到最大重连次数，退出");
                System.exit(0);
            }

            @Override
            public void packetReceived(Session session, Packet packet) {
                String fullClassName = packet.getClass().getName();
                ProtocolState state = protocol.getInboundState();

                if (fullClassName.contains("ClientboundKeepAlivePacket")) {
                    heartbeatCount.incrementAndGet();
                    return;
                }

                if (state == ProtocolState.CONFIGURATION && !inGame) {
                    handleConfiguration(session, packet, fullClassName);
                }
                if (state == ProtocolState.GAME) {
                    handleGame(session, packet, fullClassName);
                }
            }
        });
        return session;
    }

    private static String formatTime(long seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分" + (seconds % 60) + "秒";
        return (seconds / 3600) + "时" + ((seconds % 3600) / 60) + "分";
    }

    private static void resetState() {
        clientInfoSent = false;
        brandResponded = false;
        inGame = false;
        keepAliveStarted = false;
        isDead = false;
        keepAliveRunning.set(false);
        posSendCount.set(0);
        heartbeatCount.set(0);
        playerNames.clear();
    }

    private static void handleConfiguration(Session session, Packet packet, String fullClassName) {
        if (fullClassName.contains("ClientboundCustomPayloadPacket") && !brandResponded) {
            log("📋", "品牌数据");
            if (!clientInfoSent) { sendClientInformation(session); clientInfoSent = true; }
            respondToBrandPacket(session, packet);
            brandResponded = true;
        } else if (fullClassName.contains("ClientboundCustomPayloadPacket")) {
            respondToBrandPacket(session, packet);
        } else if (fullClassName.contains("ClientboundSelectKnownPacks")) {
            log("📦", "已知包选择");
        } else if (fullClassName.contains("ClientboundUpdateEnabledFeatures")) {
            log("⚙", "功能更新");
        } else if (fullClassName.contains("ClientboundFinishConfigurationPacket")) {
            log("✅", "配置完成！");
        } else if (fullClassName.contains("ClientboundPingPacket")) {
            respondToPing(session, packet);
        }
    }

    private static void handleGame(Session session, Packet packet, String fullClassName) {
        if (fullClassName.contains("ClientboundLoginPacket") && fullClassName.contains("ingame")) {
            inGame = true;
            keepAliveStarted = false;
            gameJoinTime = System.currentTimeMillis();
            try { entityId = (int) packet.getClass().getMethod("getEntityId").invoke(packet); } catch (Exception e) {}
            log("🎮", "══════ 进入游戏！实体:" + entityId + " ══════");

        } else if (fullClassName.contains("ClientboundSetHealthPacket")) {
            try {
                float health = (float) packet.getClass().getMethod("getHealth").invoke(packet);
                if (health <= 0 && !isDead) {
                    isDead = true;
                    keepAliveRunning.set(false);
                    log("💀", "死亡！自动重生...");
                    final Session fs = session;
                    new Thread(() -> { try { Thread.sleep(500); sendPerformRespawn(fs); } catch (Exception e) {} }).start();
                }
                if (health > 0 && isDead) {
                    isDead = false;
                    log("✅", "已复活！");
                }
            } catch (Exception e) {}

        } else if (fullClassName.contains("ClientboundDeathCombatEventPacket")) {
            log("💀", "战斗死亡！重生...");
            isDead = true;
            keepAliveRunning.set(false);
            final Session fs = session;
            new Thread(() -> { try { Thread.sleep(1000); sendPerformRespawn(fs); } catch (Exception e) {} }).start();

        } else if (fullClassName.contains("ClientboundPlayerPositionPacket")) {
            try {
                Object pos = packet.getClass().getMethod("getPosition").invoke(packet);
                if (pos != null) {
                    try { posX = (double) pos.getClass().getMethod("getX").invoke(pos); } catch (Exception e) {}
                    try { posY = (double) pos.getClass().getMethod("getY").invoke(pos); } catch (Exception e) {}
                    try { posZ = (double) pos.getClass().getMethod("getZ").invoke(pos); } catch (Exception e) {}
                }
            } catch (Exception e) {}
            if (!keepAliveStarted && inGame && !isDead) {
                keepAliveStarted = true;
                log("📍", "位置:(" + String.format("%.0f", posX) + "," + String.format("%.0f", posY) + "," + String.format("%.0f", posZ) + ")");
                startKeepAliveThread(session);
            }

        } else if (fullClassName.contains("ClientboundChunkBatchFinishedPacket")) {
            sendPlayerLoaded(session);
            log("📦", "区块加载完成");

        } else if (fullClassName.contains("ClientboundPlayerChatPacket")) {
            try {
                String message = extractChatMessage(packet);
                if (message != null && !message.isEmpty()) {
                    String sender = "玩家";
                    // 从 getName() 提取玩家名
                    try {
                        Object nameObj = packet.getClass().getMethod("getName").invoke(packet);
                        if (nameObj != null) {
                            String rawName = nameObj.toString();
                            if (rawName.contains("content=\"")) {
                                int start = rawName.indexOf("content=\"") + 9;
                                int end = rawName.indexOf("\"", start);
                                if (end > start) sender = rawName.substring(start, end);
                            }
                        }
                    } catch (Exception e) {}
                    // 备用：从缓存查找
                    if (sender.equals("玩家")) {
                        try {
                            Object senderObj = packet.getClass().getMethod("getSender").invoke(packet);
                            if (senderObj instanceof UUID) {
                                String cached = playerNames.get((UUID) senderObj);
                                if (cached != null) sender = cached;
                            }
                        } catch (Exception e) {}
                    }
                    log("💬", sender + ": " + message);
                }
            } catch (Exception e) {}

        } else if (fullClassName.contains("ClientboundSystemChatPacket")) {
            try {
                String rawText = packet.toString();
                String message = extractChatMessage(packet);

                // 从原始文本提取 UUID→玩家名
                if (rawText.contains("id=") && rawText.contains("name=")) {
                    try {
                        int idStart = rawText.indexOf("id=") + 3;
                        int idEnd = rawText.indexOf(",", idStart);
                        if (idEnd < 0) idEnd = idStart + 36;
                        String uuidStr = rawText.substring(idStart, Math.min(idEnd, rawText.length()));
                        int nameStart = rawText.indexOf("name=") + 5;
                        int nameEnd = rawText.indexOf(",", nameStart);
                        if (nameEnd < 0) nameEnd = rawText.indexOf("}", nameStart);
                        if (nameEnd < 0) nameEnd = rawText.length();
                        String extractedName = rawText.substring(nameStart, Math.min(nameEnd, rawText.length()));
                        extractedName = extractedName.replaceAll("[^a-zA-Z0-9_]", "");
                        if (uuidStr.length() >= 32 && !extractedName.isEmpty()) {
                            UUID uuid = UUID.fromString(uuidStr.replaceFirst(
                                    "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]+)",
                                    "$1-$2-$3-$4-$5"));
                            if (!extractedName.equals(playerName) && !playerNames.containsKey(uuid)) {
                                log("👋", extractedName + " 加入了游戏");
                            }
                            playerNames.put(uuid, extractedName);
                        }
                    } catch (Exception e) {}
                }

                if (message != null && !message.isEmpty()) {
                    message = message.replaceAll("TextComponentImpl\\{content=\"([^\"]*)\"[^}]*\\}", "$1");
                    message = message.replaceAll(", children=\\[\\]", "");
                    message = message.replaceAll("StyleImpl\\{[^}]*\\}", "");
                    message = message.replaceAll("HoverEvent\\{[^}]*\\}", "");
                    message = message.replaceAll("ClickEvent\\{[^}]*\\}", "");
                    message = message.replaceAll("ShowEntity\\{[^}]*\\}", "");
                    message = message.trim();
                    if (!message.isEmpty() && !message.equals("{}")) log("📢", message);
                }
            } catch (Exception e) {}

        } else if (fullClassName.contains("ClientboundServerDataPacket")) {
            try {
                Object motd = packet.getClass().getMethod("getMotd").invoke(packet);
                if (motd != null) {
                    String text = motd.toString();
                    text = text.replaceAll("§[0-9a-fk-or]", "");
                    text = text.replaceAll("TextComponentImpl\\{content=\"([^\"]*)\"[^}]*\\}", "$1");
                    text = text.replaceAll(", children=\\[\\]", "");
                    text = text.replaceAll("StyleImpl\\{[^}]*\\}", "");
                    if (!text.trim().isEmpty()) log("🏠", "MOTD: " + text.trim());
                }
            } catch (Exception e) {}
        }
    }

    private static void sendPlayerLoaded(Session session) {
        try {
            Class<?> c = Class.forName("org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket");
            java.lang.reflect.Field f = c.getDeclaredField("INSTANCE");
            f.setAccessible(true);
            session.send((Packet) f.get(null));
        } catch (Exception e) {}
    }

    private static void sendPerformRespawn(Session session) {
        try {
            Class<?> cmdClass = Class.forName(
                    "org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket");
            for (java.lang.reflect.Constructor<?> c : cmdClass.getConstructors()) {
                try {
                    Class<?>[] pts = c.getParameterTypes();
                    Object[] args = new Object[pts.length];
                    for (int i = 0; i < pts.length; i++) {
                        if (pts[i].isEnum()) {
                            for (Object ec : pts[i].getEnumConstants()) {
                                if (ec.toString().contains("RESPAWN")) { args[i] = ec; break; }
                            }
                            if (args[i] == null) args[i] = pts[i].getEnumConstants()[0];
                        } else if (pts[i] == int.class) args[i] = 0;
                        else args[i] = null;
                    }
                    session.send((Packet) c.newInstance(args));
                    return;
                } catch (Exception e) {}
            }
        } catch (Exception e) {}
    }

    private static void startKeepAliveThread(Session session) {
        if (keepAliveRunning.getAndSet(true)) return;
        log("💚", "保活线程已启动");
        new Thread(() -> {
            sendPlayerPosition(session);
            sendPlayerPosition(session);
            while (inGame && session.isConnected() && keepAliveRunning.get() && !isDead) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                if (session.isConnected() && inGame && !isDead) {
                    sendPlayerPosition(session);
                    int count = posSendCount.incrementAndGet();
                    if (count % 60 == 0) {
                        long elapsed = (System.currentTimeMillis() - gameJoinTime) / 1000;
                        log("💚", "在线 " + formatTime(elapsed) + " | (" + String.format("%.0f", posX) + "," + String.format("%.0f", posY) + "," + String.format("%.0f", posZ) + ")");
                    }
                }
            }
        }).start();
    }

    private static void sendPlayerPosition(Session session) {
        try {
            Class<?> c = Class.forName(
                    "org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket");
            session.send((Packet) c.getConstructor(boolean.class, boolean.class, double.class, double.class, double.class, float.class, float.class)
                    .newInstance(true, true, posX, posY, posZ, yaw, pitch));
        } catch (Exception e) {}
    }

    private static String extractChatMessage(Packet packet) {
        try {
            try {
                Object content = packet.getClass().getMethod("getContent").invoke(packet);
                if (content != null) {
                    String text = content.toString();
                    text = text.replaceAll("§[0-9a-fk-or]", "");
                    text = text.replaceAll("TranslationArgumentImpl\\{[^}]*\\}", "");
                    text = text.replaceAll("TextComponentImpl\\{content=\"([^\"]*)\"[^}]*\\}", "$1");
                    if (!text.trim().isEmpty()) return text;
                }
            } catch (Exception e) {}
            try {
                Object message = packet.getClass().getMethod("getMessage").invoke(packet);
                if (message != null) {
                    String text = message.toString();
                    text = text.replaceAll("§[0-9a-fk-or]", "");
                    text = text.replaceAll("TranslationArgumentImpl\\{[^}]*\\}", "");
                    text = text.replaceAll("TextComponentImpl\\{content=\"([^\"]*)\"[^}]*\\}", "$1");
                    if (!text.trim().isEmpty()) return text;
                }
            } catch (Exception e) {}
        } catch (Exception e) {}
        return null;
    }

    private static void sendClientInformation(Session session) {
        try {
            Class<?> cc = Class.forName("org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility");
            Class<?> hc = Class.forName("org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference");
            Class<?> pc = Class.forName("org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus");
            Class<?> ic = Class.forName("org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket");
            session.send((Packet) ic.getConstructor(String.class, int.class, cc, boolean.class, java.util.List.class, hc, boolean.class, boolean.class, pc)
                    .newInstance("zh_CN", 8, Enum.valueOf((Class)cc, "FULL"), true, java.util.Collections.emptyList(),
                            Enum.valueOf((Class)hc, "RIGHT_HAND"), true, true, Enum.valueOf((Class)pc, "ALL")));
        } catch (Exception e) {}
    }

    private static void respondToBrandPacket(Session session, Packet packet) {
        try {
            Class<?> cpc = Class.forName("org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket");
            for (java.lang.reflect.Constructor<?> c : cpc.getConstructors()) {
                try {
                    Class<?>[] pts = c.getParameterTypes();
                    Object[] args = new Object[pts.length];
                    for (int i = 0; i < pts.length; i++) {
                        if (pts[i] == String.class) args[i] = "minecraft:brand";
                        else if (pts[i] == byte[].class) args[i] = new byte[]{0x07,0x76,0x61,0x6e,0x69,0x6c,0x6c,0x61};
                        else try { args[i] = io.netty.buffer.Unpooled.wrappedBuffer(new byte[]{0x07,0x76,0x61,0x6e,0x69,0x6c,0x6c,0x61}); } catch (Exception e) { args[i] = null; }
                    }
                    session.send((Packet) c.newInstance(args));
                    break;
                } catch (Exception e) {}
            }
        } catch (Exception e) {}
    }

    private static void respondToPing(Session session, Packet packet) {
        try {
            int id = (int) packet.getClass().getMethod("getId").invoke(packet);
            Class<?> pc = Class.forName("org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket");
            session.send((Packet) pc.getConstructor(int.class).newInstance(id));
        } catch (Exception e) {}
    }

    private static YggdrasilAuthResult authenticate(String username, String password) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            JsonObject body = new JsonObject();
            body.addProperty("username", username);
            body.addProperty("password", password);
            JsonObject agent = new JsonObject();
            agent.addProperty("name", "Minecraft");
            agent.addProperty("version", 1);
            body.add("agent", agent);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(YGGDRASIL_API + "/authserver/authenticate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                String token = json.get("accessToken").getAsString();
                JsonObject profile = json.get("selectedProfile").getAsJsonObject();
                UUID uuid = fromYggdrasilId(profile.get("id").getAsString());
                return new YggdrasilAuthResult(new GameProfile(uuid, profile.get("name").getAsString()), token, json.get("clientToken").getAsString());
            }
        } catch (Exception e) {}
        return null;
    }

    private static UUID fromYggdrasilId(String id) {
        return UUID.fromString(id.replaceFirst("([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]+)", "$1-$2-$3-$4-$5"));
    }

    private static class YggdrasilAuthResult {
        final GameProfile profile; final String accessToken; final String clientToken;
        YggdrasilAuthResult(GameProfile p, String t, String c) { profile = p; accessToken = t; clientToken = c; }
    }
}