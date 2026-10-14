package ph.zenith.advcard;

import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public class AdvCardPlugin extends JavaPlugin implements Listener {

    private HttpClient http;
    private Font baseFont;

    @Override
    public void onEnable() {
        System.setProperty("java.awt.headless", "true");
        saveDefaultConfig();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        loadFont();
        getServer().getPluginManager().registerEvents(this, this);
    }

    private void loadFont() {
        baseFont = null;
        File f = new File(getDataFolder(), "font.ttf");
        if (f.exists()) {
            try {
                baseFont = Font.createFont(Font.TRUETYPE_FONT, f);
            } catch (Exception ex) {
                getLogger().warning("Could not load font.ttf: " + ex);
            }
        }
        if (baseFont == null) {
            baseFont = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        AdvancementDisplay d = e.getAdvancement().getDisplay();
        if (d == null || !d.doesAnnounceToChat()) return;
        Player p = e.getPlayer();
        sendCard(p.getName(), p.getUniqueId(), d.frame().name(),
                plain(d.title()), plain(d.description()), null);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("advcard.admin")) {
            s.sendMessage(Component.text("No permission."));
            return true;
        }
        if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadFont();
            s.sendMessage(Component.text("AdvCard reloaded."));
            return true;
        }
        if (a.length > 0 && a[0].equalsIgnoreCase("test")) {
            UUID id = (s instanceof Player p) ? p.getUniqueId() : new UUID(0L, 1L);
            s.sendMessage(Component.text("Sending test card..."));
            sendCard(s.getName(), id, "TASK", "Sweet Dreams",
                    "Sleep in a Bed to change your respawn point", s);
            return true;
        }
        s.sendMessage(Component.text("Usage: /advcard <test|reload>"));
        return true;
    }

    private void sendCard(String name, UUID uuid, String frame, String title, String desc,
                          CommandSender report) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                byte[] png = render(name, uuid, frame, title, desc);
                post(png);
                if (report != null) report.sendMessage(Component.text("Done. Check Discord."));
            } catch (Throwable ex) {
                getLogger().warning("AdvCard failed: " + ex);
                if (report != null) report.sendMessage(Component.text("Failed: " + ex));
            }
        });
    }

    private byte[] render(String name, UUID uuid, String frame, String title, String desc)
            throws IOException {
        BufferedImage head = fetchHead(uuid);

        String verb = switch (frame) {
            case "CHALLENGE" -> "has completed the challenge";
            case "GOAL" -> "has reached the goal";
            default -> "has made the advancement";
        };
        Color accent = frame.equals("CHALLENGE") ? new Color(0xFF55FF) : new Color(0x55FF55);

        Font small = baseFont.deriveFont(Font.PLAIN, 15f);
        Font big = baseFont.deriveFont(Font.BOLD, 22f);

        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g0 = tmp.createGraphics();
        FontMetrics fs = g0.getFontMetrics(small);
        FontMetrics fb = g0.getFontMetrics(big);
        String line1 = name + " " + verb;
        int textW = Math.max(fs.stringWidth(line1),
                Math.max(fb.stringWidth(title), fs.stringWidth(desc)));
        g0.dispose();

        int pad = 16, headSize = 64, h = 100;
        int x0 = 6 + pad + headSize + pad;
        int w = Math.max(360, x0 + textW + pad);

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        g.setColor(new Color(0x1E1E22));
        g.fillRoundRect(0, 0, w, h, 14, 14);
        g.setColor(accent);
        g.fillRect(0, 8, 5, h - 16);

        int hy = (h - headSize) / 2;
        if (head != null) {
            g.drawImage(head, 6 + pad, hy, headSize, headSize, null);
        } else {
            g.setColor(new Color(0x55555C));
            g.fillRect(6 + pad, hy, headSize, headSize);
            g.setColor(Color.WHITE);
            g.setFont(big);
            g.drawString("?", 6 + pad + headSize / 2 - 6, hy + headSize / 2 + 8);
        }

        g.setFont(small);
        g.setColor(new Color(0xAAAAAA));
        g.drawString(line1, x0, 30);
        g.setFont(big);
        g.setColor(accent);
        g.drawString(title, x0, 57);
        g.setFont(small);
        g.setColor(Color.WHITE);
        g.drawString(desc, x0, 82);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // Bedrock (Floodgate) players have a UUID starting with zeros -> no Mojang skin
    private BufferedImage fetchHead(UUID uuid) {
        if (uuid.getMostSignificantBits() == 0L) return null;
        try {
            HttpRequest r = HttpRequest.newBuilder(
                            URI.create("https://mc-heads.net/avatar/" + uuid + "/64"))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            HttpResponse<byte[]> resp = http.send(r, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200) {
                return ImageIO.read(new ByteArrayInputStream(resp.body()));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void post(byte[] png) throws Exception {
        String url = getConfig().getString("webhook-url", "");
        if (url.isBlank() || url.contains("PASTE")) {
            throw new IllegalStateException("webhook-url is not set in config.yml");
        }
        String boundary = "----advcard" + System.nanoTime();

        StringBuilder json = new StringBuilder("{\"username\":")
                .append(q(getConfig().getString("webhook-name", "Advancements")));
        String av = getConfig().getString("webhook-avatar-url", "");
        if (!av.isBlank()) json.append(",\"avatar_url\":").append(q(av));
        json.append(",\"attachments\":[{\"id\":0,\"filename\":\"advancement.png\"}]}");

        ByteArrayOutputStream o = new ByteArrayOutputStream();
        w(o, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + json + "\r\n");
        w(o, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; "
                + "filename=\"advancement.png\"\r\nContent-Type: image/png\r\n\r\n");
        o.write(png);
        w(o, "\r\n--" + boundary + "--\r\n");

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(o.toByteArray()))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) {
            throw new IOException("Webhook error " + resp.statusCode() + ": " + resp.body());
        }
    }

    private static void w(ByteArrayOutputStream o, String s) throws IOException {
        o.write(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    private static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }
            }
