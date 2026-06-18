import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class BotConfig {
    private static final Properties props = new Properties();
    private static final String CONFIG_FILE = "config.properties";
    private static boolean initialized = false;

    // 缓存：避免每次启动都请求 API
    private static List<String[]> cachedProfiles = null;

    /**
     * 初始化配置
     */
    public static void init() {
        if (initialized) return;
        initialized = true;

        File configFile = new File(CONFIG_FILE);

        if (!configFile.exists()) {
            createConfigInteractively();
        }

        // 加载配置文件
        try (InputStream input = new FileInputStream(configFile)) {
            props.load(input);
        } catch (IOException e) {
            throw new RuntimeException("读取配置文件失败: " + e.getMessage(), e);
        }

        // 检查是否需要选择角色
        if (!isConfigComplete()) {
            completeConfig();
        }
    }

    /**
     * 检查配置是否完整（是否需要选择角色）
     */
    private static boolean isConfigComplete() {
        String authMode = props.getProperty("mc.auth-mode", "online");

        // 离线模式不需要角色选择
        if (authMode.equalsIgnoreCase("offline")) {
            return props.containsKey("mc.username") && !props.getProperty("mc.username").isEmpty();
        }

        // 检查是否设置了"记住角色"选项
        String rememberChoice = props.getProperty("mc.remember-profile", "true");
        if (rememberChoice.equalsIgnoreCase("false") || rememberChoice.equalsIgnoreCase("no")) {
            // 每次启动都选择，所以配置不完整
            return false;
        }

        // 检查是否有角色名
        return props.containsKey("mc.username") && !props.getProperty("mc.username").isEmpty();
    }

    /**
     * 完成配置（选择角色）
     */
    private static void completeConfig() {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║     选择游戏角色                     ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println();

        String authMode = props.getProperty("mc.auth-mode", "online");

        if (authMode.equalsIgnoreCase("offline")) {
            Scanner scanner = new Scanner(System.in);
            System.out.print("离线用户名: ");
            String name = scanner.nextLine().trim();
            if (!name.isEmpty()) {
                props.setProperty("mc.username", name);
                saveConfig();
            }
            return;
        }

        // 在线模式 - 获取角色列表
        String apiUrl = props.getProperty("mc.yggdrasil-api", "https://littleskin.cn/api/yggdrasil");
        String email = props.getProperty("mc.email", "");
        String password = props.getProperty("mc.password", "");

        System.out.println("🔐 正在获取角色列表...");
        List<String[]> profiles = fetchProfiles(apiUrl, email, password);

        if (profiles == null || profiles.isEmpty()) {
            System.out.println("⚠ 无法获取角色列表");
            Scanner scanner = new Scanner(System.in);
            System.out.print("请手动输入角色名: ");
            String name = scanner.nextLine().trim();
            if (!name.isEmpty()) {
                props.setProperty("mc.username", name);
                saveConfig();
            }
            return;
        }

        String selectedProfile;

        if (profiles.size() == 1) {
            selectedProfile = profiles.get(0)[0];
            System.out.println("✅ 只有一个角色: " + selectedProfile);
            System.out.println("   UUID: " + profiles.get(0)[1]);
        } else {
            System.out.println("发现 " + profiles.size() + " 个角色:");
            for (int i = 0; i < profiles.size(); i++) {
                String uuid = profiles.get(i)[1];
                String shortUuid = uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
                System.out.println("  " + (i + 1) + ". " + profiles.get(i)[0] + " (UUID: " + shortUuid + "...)");
            }

            Scanner scanner = new Scanner(System.in);
            int choice = 1;
            while (true) {
                System.out.print("请选择角色 (1-" + profiles.size() + "): ");
                try {
                    String input = scanner.nextLine().trim();
                    if (!input.isEmpty()) {
                        choice = Integer.parseInt(input);
                        if (choice >= 1 && choice <= profiles.size()) break;
                    } else {
                        break; // 默认第一个
                    }
                } catch (NumberFormatException e) {}
                System.out.println("⚠ 请输入 1-" + profiles.size() + " 之间的数字");
            }
            selectedProfile = profiles.get(choice - 1)[0];
        }

        props.setProperty("mc.username", selectedProfile);

        // 询问是否记住选择
        if (profiles.size() > 1) {
            System.out.println();
            System.out.println("💡 提示: 可以设置 '记住角色' 避免每次启动都选择");
            System.out.println("   配置文件中的 mc.remember-profile=true 则记住，false 则每次选择");
        }

        saveConfig();
        System.out.println("✅ 已选择角色: " + selectedProfile);
        System.out.println();
    }

    /**
     * 保存配置到文件
     */
    private static void saveConfig() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(CONFIG_FILE))) {
            // 按顺序写入
            writer.println("# MCBot 配置文件");
            writer.println("# 生成时间: " + new java.util.Date());
            writer.println();
            writer.println("# 服务器设置");
            writeProp(writer, "mc.host");
            writeProp(writer, "mc.port");
            writer.println();
            writer.println("# 认证设置");
            writeProp(writer, "mc.auth-mode");
            writeProp(writer, "mc.yggdrasil-api");
            writeProp(writer, "mc.email");
            writeProp(writer, "mc.password");
            writer.println();
            writer.println("# 角色选择");
            writer.println("# mc.remember-profile=true  记住角色，启动时不询问");
            writer.println("# mc.remember-profile=false 每次启动都询问选择角色");
            writeProp(writer, "mc.remember-profile");
            writeProp(writer, "mc.username");
            writer.println();
            writer.println("# 其他设置");
            writer.println("# mc.language=zh_CN");
        } catch (IOException e) {
            System.err.println("无法保存配置文件: " + e.getMessage());
        }
    }

    private static void writeProp(PrintWriter writer, String key) {
        String value = props.getProperty(key);
        if (value != null) {
            writer.println(key + "=" + value);
        }
    }

    /**
     * 交互式创建配置文件
     */
    private static void createConfigInteractively() {
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║     首次运行 - 配置 MCBot           ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println();

        Scanner scanner = new Scanner(System.in);

        // 服务器配置
        System.out.println("── 服务器配置 ──");
        System.out.print("服务器地址 (默认 localhost): ");
        String host = scanner.nextLine().trim();
        if (host.isEmpty()) host = "localhost";
        props.setProperty("mc.host", host);

        System.out.print("服务器端口 (默认 25565): ");
        String portStr = scanner.nextLine().trim();
        int port = 25565;
        if (!portStr.isEmpty()) {
            try { port = Integer.parseInt(portStr); } catch (NumberFormatException e) {}
        }
        props.setProperty("mc.port", String.valueOf(port));

        // 认证配置
        System.out.println();
        System.out.println("── 认证配置 ──");
        System.out.print("认证模式 (1=在线/Yggdrasil, 2=离线, 默认 1): ");
        String modeChoice = scanner.nextLine().trim();
        boolean offline = modeChoice.equals("2");
        props.setProperty("mc.auth-mode", offline ? "offline" : "online");

        if (!offline) {
            System.out.print("Yggdrasil API (默认 https://littleskin.cn/api/yggdrasil): ");
            String api = scanner.nextLine().trim();
            if (api.isEmpty()) api = "https://littleskin.cn/api/yggdrasil";
            props.setProperty("mc.yggdrasil-api", api);

            System.out.print("邮箱: ");
            props.setProperty("mc.email", scanner.nextLine().trim());

            System.out.print("密码: ");
            props.setProperty("mc.password", scanner.nextLine().trim());

            // 询问是否记住角色
            System.out.println();
            System.out.println("── 角色选择设置 ──");
            System.out.println("  true  = 记住角色，每次启动自动使用 (适合单开)");
            System.out.println("  false = 每次启动都询问选择角色 (适合多开)");
            System.out.print("记住角色？(默认 true): ");
            String remember = scanner.nextLine().trim().toLowerCase();
            if (remember.equals("false") || remember.equals("no") || remember.equals("0")) {
                props.setProperty("mc.remember-profile", "false");
            } else {
                props.setProperty("mc.remember-profile", "true");
            }
        } else {
            System.out.print("离线用户名: ");
            props.setProperty("mc.username", scanner.nextLine().trim());
            props.setProperty("mc.remember-profile", "true");
        }

        saveConfig();

        System.out.println();
        System.out.println("✅ 配置已保存到 " + CONFIG_FILE);
        System.out.println();

        // 如果是多开模式，立即选择角色
        if (!offline && "false".equals(props.getProperty("mc.remember-profile"))) {
            completeConfig();
        }
    }

    /**
     * 从 Yggdrasil API 获取角色列表
     */
    private static List<String[]> fetchProfiles(String apiUrl, String email, String password) {
        if (cachedProfiles != null) return cachedProfiles;

        try {
            HttpClient client = HttpClient.newHttpClient();

            JsonObject requestBody = new JsonObject();
            requestBody.addProperty("username", email);
            requestBody.addProperty("password", password);
            JsonObject agent = new JsonObject();
            agent.addProperty("name", "Minecraft");
            agent.addProperty("version", 1);
            requestBody.add("agent", agent);
            requestBody.addProperty("requestUser", true);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/authserver/authenticate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

                List<String[]> profiles = new ArrayList<>();

                if (json.has("availableProfiles")) {
                    JsonArray availableProfiles = json.getAsJsonArray("availableProfiles");
                    for (int i = 0; i < availableProfiles.size(); i++) {
                        JsonObject profile = availableProfiles.get(i).getAsJsonObject();
                        profiles.add(new String[]{
                                profile.get("name").getAsString(),
                                profile.get("id").getAsString()
                        });
                    }
                }

                if (profiles.isEmpty() && json.has("selectedProfile")) {
                    JsonObject profile = json.getAsJsonObject("selectedProfile");
                    profiles.add(new String[]{
                            profile.get("name").getAsString(),
                            profile.get("id").getAsString()
                    });
                }

                cachedProfiles = profiles;
                return profiles;
            }
        } catch (Exception e) {
            System.out.println("⚠ 获取角色列表失败: " + e.getMessage());
        }
        return null;
    }

    // ═══════════════════════════════════════════
    // 公共 API
    // ═══════════════════════════════════════════

    public static String get(String key, String defaultValue) {
        init();
        return props.getProperty(key, defaultValue);
    }

    public static int getInt(String key, int defaultValue) {
        init();
        try {
            return Integer.parseInt(props.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        init();
        String value = props.getProperty(key);
        if (value == null) return defaultValue;
        return value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("yes");
    }

    public static void reload() {
        initialized = false;
        cachedProfiles = null;
        props.clear();
        init();
    }

    public static void showConfig() {
        init();
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════╗");
        System.out.println("  ║        当前配置                      ║");
        System.out.println("  ╠══════════════════════════════════════╣");
        System.out.println("  ║ 服务器: " + String.format("%-28s", get("mc.host", "?") + ":" + getInt("mc.port", 25565)) + "║");
        String mode = get("mc.auth-mode", "online");
        System.out.println("  ║ 模式:   " + String.format("%-28s", mode.equals("offline") ? "离线" : "在线(Yggdrasil)") + "║");
        if (!mode.equals("offline")) {
            System.out.println("  ║ API:    " + String.format("%-28s", get("mc.yggdrasil-api", "?")) + "║");
            System.out.println("  ║ 邮箱:   " + String.format("%-28s", get("mc.email", "?")) + "║");
        }
        System.out.println("  ║ 角色:   " + String.format("%-28s", get("mc.username", "?")) + "║");
        String remember = get("mc.remember-profile", "true");
        System.out.println("  ║ 记住:   " + String.format("%-28s", remember.equals("false") ? "每次询问 (适合多开)" : "记住角色 (适合单开)") + "║");
        System.out.println("  ╚══════════════════════════════════════╝");
        System.out.println();
    }
}