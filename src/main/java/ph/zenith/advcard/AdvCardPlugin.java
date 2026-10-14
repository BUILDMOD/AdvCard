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
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public class AdvCardPlugin extends JavaPlugin implements Listener {

    private static final String DEFAULT_TEXTURES =
            "https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.21.4/assets/minecraft/textures";

    private HttpClient http;

    @Override
    public void onEnable() {
        System.setProperty("java.awt.headless", "true");
        saveDefaultConfig();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        AdvancementDisplay d = e.getAdvancement().getDisplay();
        if (d == null || !d.doesAnnounceToChat()) return;
        Player p = e.getPlayer();
        String iconKey = d.icon().getType().getKey().getKey();
        sendCard(p.getName(), p.getUniqueId(), d.frame().name(),
                plain(d.title()), plain(d.description()), iconKey, null);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("advcard.admin")) {
            s.sendMessage(Component.text("No permission."));
            return true;
        }
        if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            s.sendMessage(Component.text("AdvCard reloaded."));
            return true;
        }
        if (a.length > 0 && a[0].equalsIgnoreCase("test")) {
            UUID id = (s instanceof Player p) ? p.getUniqueId() : new UUID(0L, 1L);
            s.sendMessage(Component.text("Sending test card..."));
            sendCard(s.getName(), id, "TASK", "Sweet Dreams",
                    "Sleep in a Bed to change your respawn point", "red_bed", s);
            return true;
        }
        s.sendMessage(Component.text("Usage: /advcard <test|reload>"));
        return true;
    }

    private void sendCard(String name, UUID uuid, String frame, String title, String desc,
                          String iconKey, CommandSender report) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                byte[] icon = null;
                try {
                    icon = buildIcon(iconKey, frame);
                } catch (Throwable t) {
                    getLogger().warning("Icon failed (" + iconKey + "): " + t);
                }
                post(name, uuid, frame, title, desc, icon);
                if (report != null) report.sendMessage(Component.text("Done. Check Discord."));
            } catch (Throwable ex) {
                getLogger().warning("AdvCard failed: " + ex);
                if (report != null) report.sendMessage(Component.text("Failed: " + ex));
            }
        });
    }

    // Gold frame + item texture, like the vanilla advancement icon
    private byte[] buildIcon(String key, String frame) throws IOException {
        BufferedImage tex = fetchTexture(key);
        if (tex == null) return null;
        if (tex.getHeight() > tex.getWidth()) {
            tex = tex.getSubimage(0, 0, tex.getWidth(), tex.getWidth());
        }
        int size = 128;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, size, size);
        g.setColor(frame.equals("CHALLENGE") ? new Color(0x7F3FBF) : new Color(0xD9A21B));
        g.fillRect(6, 6, size - 12, size - 12);
        g.drawImage(tex, 24, 24, 80, 80, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private BufferedImage fetchTexture(String key) {
        String base = getConfig().getString("texture-base", DEFAULT_TEXTURES);
        String[] paths = {
                "item/" + key, "block/" + key, "block/" + key + "_front",
                "block/" + key + "_side", "block/" + key + "_top"
        };
        for (String p : paths) {
            try {
                HttpRequest r = HttpRequest.newBuilder(URI.create(base + "/" + p + ".png"))
                        .timeout(Duration.ofSeconds(8)).GET().build();
                HttpResponse<byte[]> resp = http.send(r, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 200) {
                    BufferedImage bi = ImageIO.read(new ByteArrayInputStream(resp.body()));
                    if (bi != null) return bi;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void post(String name, UUID uuid, String frame, String title, String desc,
                      byte[] icon) throws Exception {
        String url = getConfig().getString("webhook-url", "");
        if (url.isBlank() || url.contains("PASTE")) {
            throw new IllegalStateException("webhook-url is not set in config.yml");
        }

        String verb = switch (frame) {
            case "CHALLENGE" -> "has completed the challenge";
            case "GOAL" -> "has reached the goal";
            default -> "has made the advancement";
        };
        int color = frame.equals("CHALLENGE") ? 0xFF55FF : 0x55FF55;

        StringBuilder emb = new StringBuilder("{\"color\":").append(color)
                .append(",\"author\":{\"name\":").append(q(name + " " + verb + " " + title));
        // Bedrock (Floodgate) players have a UUID starting with zeros -> no Mojang skin
        if (uuid.getMostSignificantBits() != 0L) {
            emb.append(",\"icon_url\":")
                    .append(q("https://mc-heads.net/avatar/" + uuid + "/64"));
        }
        emb.append("},\"description\":").append(q(desc));
        if (icon != null) emb.append(",\"thumbnail\":{\"url\":\"attachment://icon.png\"}");
        emb.append("}");

        StringBuilder json = new StringBuilder("{\"username\":")
                .append(q(getConfig().getString("webhook-name", "Advancements")));
        String av = getConfig().getString("webhook-avatar-url", "");
        if (!av.isBlank()) json.append(",\"avatar_url\":").append(q(av));
        json.append(",\"embeds\":[").append(emb).append("]");
        if (icon != null) json.append(",\"attachments\":[{\"id\":0,\"filename\":\"icon.png\"}]");
        json.append("}");

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15));
        if (icon == null) {
            rb.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.toString()));
        } else {
            String boundary = "----advcard" + System.nanoTime();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            w(o, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                    + "Content-Type: application/json\r\n\r\n" + json + "\r\n");
            w(o, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; "
                    + "filename=\"icon.png\"\r\nContent-Type: image/png\r\n\r\n");
            o.write(icon);
            w(o, "\r\n--" + boundary + "--\r\n");
            rb.header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(o.toByteArray()));
        }
        HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
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
