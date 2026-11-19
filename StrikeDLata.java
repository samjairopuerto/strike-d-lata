import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STRIKE D LATA - a 16:9 arcade Tumbang Preso game.
 *
 * Course : IT 373A - Event Driven Programming (Java)
 * Build  : javac StrikeDLata.java
 * Run    : java StrikeDLata            (then open http://localhost:8080)
 *          java StrikeDLata 9000       (optional custom port)
 *
 * Architecture
 *   Java side  : com.sun.net.httpserver.HttpServer on localhost. It owns the game DATA:
 *                - /api/levels : the 60-level difficulty table (6 biomes) (Taya tier speed, safe line,
 *                                star targets, obstacle tiers, wind, 2-player goals ...)
 *                - /api/tips   : SDG 13 disaster-safety lessons
 *                - /api/save   : all player profiles (stars, unlocks, coins, shop) kept in
 *                                strike_d_lata_save.json next to the program
 *                - /api/leaderboard : Top-10 global ranking (strike_d_lata_board.tsv)
 *                and serves the HTML5 canvas client at "/".
 *   Client side: an event-driven canvas engine (keyboard + mouse listeners, an event bus,
 *                a state machine STANDOFF -> AIRBORNE -> SCRAMBLE -> LEVEL_CLEAR).
 */
public final class StrikeDLata {

    private static final int DEFAULT_PORT = 8080;
    private static final Path SAVE_FILE = Path.of("strike_d_lata_save.json");
    private static final int MAX_SAVE_BYTES = 96 * 1024;
    private static volatile String saveJson = "{}";

    // ---- global leaderboard (best total score per player name, top 10 served) ----
    private record Entry(String name, int score, int stars, int level) { }
    private static final Path BOARD_FILE = Path.of("strike_d_lata_board.tsv");
    private static final List<Entry> BOARD = new ArrayList<>();
    private static final Pattern P_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]{1,12})\"");
    private static final Pattern P_SCORE = Pattern.compile("\"score\"\\s*:\\s*(\\d{1,9})");
    private static final Pattern P_STARS = Pattern.compile("\"stars\"\\s*:\\s*(\\d{1,3})");
    private static final Pattern P_LEVEL = Pattern.compile("\"level\"\\s*:\\s*(\\d{1,3})");

    // =====================================================================================
    //  JAVA: level table + tips (the authoritative game data)
    // =====================================================================================

    /** One row of the 60-level difficulty table (6 biomes). */
    private record Level(int n, int env, int tier, double tayaSpeed, double reaction, double resetTime,
                         double predict, int lineY, int timeLimit, int fastTime, int targetScore, int targetScore2p,
                         int hits, int ammo, double meterSpeed, int canR,
                         int puddles, int blockers, int movers, double gust, int boss) {

        static Level of(int n) {
            int env = Math.min(4, (n - 1) / 10);                     // 0 street 1 school 2 city 3 beach 4 forest 5 haunted
            int eff = Math.min(30, 1 + (int) Math.round((n - 1) * 29.0 / 49.0)); // difficulty index inside the 30-step curve
            int tier = (n - 1) / 5;                                 // 6 difficulty tiers of 5 levels
            double tayaSpeed = 44 * Math.min(1 + 0.15 * tier, 1.75 + 0.025 * Math.max(0, tier - 5)); // +15% per tier (capped late)
            double reaction = Math.max(0.20, 0.95 - 0.13 * tier);   // seconds before the Taya reacts
            double resetTime = Math.max(1.3, 2.8 - 0.3 * tier);     // seconds to stand the can up
            double predict = Math.min(1.0, 0.35 + 0.13 * tier);     // how far ahead she predicts you
            int lineY = switch (env) { case 0 -> 614; case 1 -> 620; case 2 -> 628; case 3 -> 626; default -> 630; }; // safe zone ~17-19% of 720
            int timeLimit = 170 - (int) Math.round(1.2 * (n - 1));                      // seconds; reaching 0 FAILS the level
            int fastTime = (int) Math.round(timeLimit * 0.55);      // 2-star target time
            int hits = (n % 10 == 0) ? 4 + n / 10 : 2 + (eff - 1) / 6;                             // cans to knock down
            // 1-star score target: ~20% above the plain (no perfect throw) score for 'hits' cans
            double plain = 100.0 * (hits + 0.25 * hits * (hits - 1) / 2.0);
            int targetScore = (int) (Math.round(plain * 1.2 / 10.0) * 10);
            int targetScore2p = (int) (Math.round(targetScore * 1.7 / 10.0) * 10); // combined co-op goal
            int ammo = (env >= 3) ? 2 : 3;                            // slippers per hitter (2P)
            double meterSpeed = 2.6 + 0.11 * (eff - 1);               // rad/s of the power meter
            int canR = switch (env) { case 0 -> 30; case 1 -> 27; case 2 -> 26; case 3 -> 24; default -> 26; };
            int puddles = eff < 4 ? 0 : Math.min(6, 2 + (eff - 4) / 4); // levels 4+ : puddles
            int blockers = eff < 4 ? 0 : Math.min(6, 2 + (eff - 4) / 5); // levels 4+ : bikes / bins / desks...
            int movers = eff < 8 ? 0 : Math.min(5, 1 + (eff - 8) / 5);  // levels 8+ : pedestrians
            double gust = eff < 8 ? 0 : 14 + 0.9 * (eff - 8);           // levels 8+ : gusty wind
            return new Level(n, env, tier, tayaSpeed, reaction, resetTime, predict, lineY, timeLimit, fastTime,
                    targetScore, targetScore2p, hits, ammo, meterSpeed, canR, puddles, blockers, movers, gust, (n % 10 == 0) ? 1 : 0);
        }

        String json() {
            return String.format(Locale.ROOT,
                    "{\"n\":%d,\"env\":%d,\"tier\":%d,\"tayaSpeed\":%.2f,\"reaction\":%.2f,\"resetTime\":%.2f,"
                            + "\"predict\":%.2f,\"lineY\":%d,\"timeLimit\":%d,\"fastTime\":%d,\"targetScore\":%d,"
                            + "\"targetScore2p\":%d,\"hits\":%d,\"ammo\":%d,\"meterSpeed\":%.2f,\"canR\":%d,"
                            + "\"puddles\":%d,\"blockers\":%d,\"movers\":%d,\"gust\":%.1f,\"boss\":%d,"
                            + "\"hazMin\":15,\"hazMax\":25}",
                    n, env, tier, tayaSpeed, reaction, resetTime, predict, lineY, timeLimit, fastTime,
                    targetScore, targetScore2p, hits, ammo, meterSpeed, canR, puddles, blockers, movers, gust, boss);
        }
    }

    private static final List<String> HAZARDS = List.of(
            "TYPHOON", "FLOOD", "EARTHQUAKE", "LANDSLIDE", "FIRE", "TORNADO", "STORM", "AMIHAN", "BROWNOUT", "ASHFALL");

    private static final Map<String, List<String>> TIPS = Map.of(
            "AMIHAN", List.of(
                    "Strong coastal winds and high tides can sweep over piers and beaches. Stay away from the shoreline when PAGASA warns of storm surge.",
                    "Fishers and boat owners should secure their bancas and avoid sailing when gale warnings are up.",
                    "Move to higher ground and follow barangay evacuation orders when waves start reaching the road.",
                    "Never take photos at the edge of big waves. Storm surge can pull people into the sea in seconds."),
            "BROWNOUT", List.of(
                    "During a power outage keep a flashlight and charged power bank ready. Avoid candles, which are a leading cause of house fires.",
                    "Unplug sensitive appliances so a power surge does not damage them when electricity returns.",
                    "Keep the fridge closed to preserve food, and check on neighbors who rely on electric medical devices.",
                    "Stay put and stay calm in the dark. Move slowly and use a light so you do not trip or get lost."),
            "ASHFALL", List.of(
                    "During ashfall wear an N95 mask and goggles, and stay indoors with windows closed. Ash irritates the lungs and eyes.",
                    "Stay out of the permanent danger zone around Mayon Volcano and follow PHIVOLCS alert levels.",
                    "Wet ash is heavy: clear it from roofs carefully, since the weight can make a roof collapse.",
                    "Lava flows and pyroclastic currents move fast. When authorities say evacuate, go right away."),
            "TYPHOON", List.of(
                    "Prepare a Go Bag before a typhoon: water, food, flashlight, first-aid kit, radio, medicines and copies of IDs.",
                    "Follow PAGASA wind signal warnings. When your barangay says evacuate, go early - do not wait for the storm.",
                    "Stay indoors away from windows. Flying debris is the biggest danger, so never play outside in strong winds.",
                    "Secure loose objects, unplug appliances, and charge phones and power banks before the storm arrives."),
            "FLOOD", List.of(
                    "Never walk or play in floodwater. It can hide open drains and carry diseases such as leptospirosis.",
                    "Move to higher ground right away. Just 15 cm of fast-moving water can knock an adult off their feet.",
                    "If water is rising inside your home, switch off the main power to prevent electrocution.",
                    "Keep canals and drains clear. Never throw trash into waterways - clean drainage helps prevent floods."),
            "EARTHQUAKE", List.of(
                    "Drop, Cover and Hold On: get under a sturdy table, cover your head and neck, and hold on until the shaking stops.",
                    "Stay away from windows, shelves and anything that can fall. Outdoors, move to an open area away from buildings.",
                    "After the shaking, check for injuries, use the stairs - never the elevator - and expect aftershocks. Follow PHIVOLCS updates.",
                    "Join your school and barangay earthquake drills and know your family meeting place and emergency exits."),
            "LANDSLIDE", List.of(
                    "After days of heavy rain, watch for cracks in the ground, tilting trees or poles, and muddy or sudden changes in streams.",
                    "If you live near a slope and the authorities order an evacuation, leave at once and never cross a landslide area to go back.",
                    "Stay away from steep slopes, riverbanks and cliffs during and after heavy rain. Listen to local warnings and MGB advisories.",
                    "Report blocked roads and slope damage to your barangay. Never drive or walk through debris - more earth may follow."),
            "FIRE", List.of(
                    "If your clothes catch fire: Stop, Drop and Roll. Cover your face and roll until the flames are out.",
                    "Stay low under the smoke, feel doors before opening them, and get out fast. Call the fire department - 911 - once you are safe.",
                    "Never leave candles, stoves or charging devices unattended. Keep matches and lighters away from children.",
                    "Know two ways out of every room and agree on a meeting place outside. Test smoke alarms and keep a fire extinguisher handy."),
            "TORNADO", List.of(
                    "Go to the lowest floor of a sturdy building and stay in an interior room away from windows. Cover your head and neck.",
                    "A greenish dark sky, loud roar and debris in the air are warning signs. Do not wait to see the funnel - take shelter immediately.",
                    "Do not hide under an overpass or try to outrun a tornado in a vehicle. Find solid shelter or lie flat in a low ditch.",
                    "After a tornado, stay away from fallen power lines and damaged buildings, and listen for PAGASA updates."),
            "STORM", List.of(
                    "When thunder roars, go indoors. No place outside is safe during a severe thunderstorm.",
                    "Stay away from tall trees, open fields, water and metal fences. Lightning strikes the highest and most open spots.",
                    "Unplug electronics, avoid using corded phones or showering, and wait 30 minutes after the last thunder before going out.",
                    "Check PAGASA thunderstorm advisories and rainfall warnings. Heavy rain can quickly cause floods and landslides."));

    private static String levelsJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int n = 1; n <= 50; n++) {
            if (n > 1) sb.append(',');
            sb.append(Level.of(n).json());
        }
        return sb.append(']').toString();
    }

    private static String tipsJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean firstKey = true;
        for (String key : HAZARDS) {
            if (!firstKey) sb.append(',');
            firstKey = false;
            sb.append('"').append(key).append("\":[");
            List<String> tips = TIPS.get(key);
            for (int i = 0; i < tips.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(escape(tips.get(i))).append('"');
            }
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // =====================================================================================
    //  JAVA: HTTP server
    // =====================================================================================

    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); }
            catch (NumberFormatException e) { System.err.println("Ignoring invalid port '" + args[0] + "'"); }
        }
        loadSave();
        loadBoard();

        HttpServer server = null;
        for (int p = port; p < port + 10 && server == null; p++) {
            try {
                server = HttpServer.create(new InetSocketAddress("localhost", p), 0);
                port = p;
            } catch (BindException e) {
                System.err.println("Port " + p + " is busy, trying the next one...");
            }
        }
        if (server == null) {
            System.err.println("Could not bind any port near " + port + ".");
            return;
        }

        final String page = buildPage();
        final String levels = levelsJson();
        final String tips = tipsJson();

        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) send(ex, 200, "text/html; charset=utf-8", page);
            else if (path.equals("/f/body.woff2") || path.equals("/f/title.woff2")) {
                byte[] fb = java.util.Base64.getDecoder().decode(path.contains("body") ? FONT_BODY : FONT_TITLE);
                ex.getResponseHeaders().set("Content-Type", "font/woff2");
                ex.getResponseHeaders().set("Cache-Control", "max-age=86400");
                ex.sendResponseHeaders(200, fb.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(fb); }
            }
            else if (path.equals("/favicon.ico")) { ex.sendResponseHeaders(204, -1); ex.close(); }
            else send(ex, 404, "text/plain; charset=utf-8", "Not found");
        });
        server.createContext("/api/levels", ex -> send(ex, 200, "application/json; charset=utf-8", levels));
        server.createContext("/api/tips", ex -> send(ex, 200, "application/json; charset=utf-8", tips));
        server.createContext("/api/save", ex -> {
            if (ex.getRequestMethod().equalsIgnoreCase("POST")) {
                byte[] raw = ex.getRequestBody().readNBytes(MAX_SAVE_BYTES + 1);
                String body = new String(raw, StandardCharsets.UTF_8).trim();
                if (raw.length <= MAX_SAVE_BYTES && body.startsWith("{") && body.endsWith("}")) {
                    saveJson = body;
                    persistSave();
                }
            }
            send(ex, 200, "application/json; charset=utf-8", saveJson);
        });

        server.createContext("/api/leaderboard", ex -> {
            if (ex.getRequestMethod().equalsIgnoreCase("POST")) {
                String body = new String(ex.getRequestBody().readNBytes(2048), StandardCharsets.UTF_8);
                boardUpdate(body);
            }
            send(ex, 200, "application/json; charset=utf-8", boardJson());
        });

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        final HttpServer running = server;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> running.stop(0)));

        String url = "http://localhost:" + port + "/";
        System.out.println("=======================================================");
        System.out.println("  STRIKE D LATA is running:  " + url);
        System.out.println("  Progress is saved in: " + SAVE_FILE.toAbsolutePath());
        System.out.println("  Press Ctrl+C to stop the server.");
        System.out.println("=======================================================");
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Throwable ignored) { /* headless machine: user opens the URL manually */ }
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // ---- leaderboard ----

    private static String clean(String s) { return s.replaceAll("[^A-Za-z0-9 _-]", "").trim(); }

    private static int num(Pattern p, String body) {
        Matcher m = p.matcher(body);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** POST body: {"name":"Sam","score":1234,"stars":12,"level":5} or {"name":"Sam","remove":true}. */
    private static synchronized void boardUpdate(String body) {
        Matcher m = P_NAME.matcher(body);
        if (!m.find()) return;
        String name = clean(m.group(1));
        if (name.isEmpty()) return;
        BOARD.removeIf(e -> e.name().equalsIgnoreCase(name) && body.contains("\"remove\":true"));
        if (!body.contains("\"remove\":true")) {
            int score = num(P_SCORE, body), stars = num(P_STARS, body), level = num(P_LEVEL, body);
            Entry old = BOARD.stream().filter(e -> e.name().equalsIgnoreCase(name)).findFirst().orElse(null);
            if (old != null) {
                if (score < old.score()) return;               // keep each player's best only
                BOARD.remove(old);
            }
            BOARD.add(new Entry(name, score, stars, level));
        }
        BOARD.sort(Comparator.comparingInt(Entry::score).reversed());
        while (BOARD.size() > 50) BOARD.remove(BOARD.size() - 1);
        persistBoard();
    }

    private static synchronized String boardJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(10, BOARD.size()); i++) {
            Entry e = BOARD.get(i);
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.ROOT, "{\"name\":\"%s\",\"score\":%d,\"stars\":%d,\"level\":%d}",
                    escape(e.name()), e.score(), e.stars(), e.level()));
        }
        return sb.append(']').toString();
    }

    private static void loadBoard() {
        try {
            if (!Files.exists(BOARD_FILE)) return;
            for (String line : Files.readAllLines(BOARD_FILE)) {
                String[] f = line.split("\t");
                if (f.length == 4) BOARD.add(new Entry(clean(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3])));
            }
            BOARD.sort(Comparator.comparingInt(Entry::score).reversed());
        } catch (IOException | NumberFormatException ignored) { /* start with an empty board */ }
    }

    private static void persistBoard() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : BOARD) sb.append(e.name()).append('\t').append(e.score()).append('\t').append(e.stars()).append('\t').append(e.level()).append('\n');
        try { Files.writeString(BOARD_FILE, sb.toString()); } catch (IOException ignored) { /* not persisted */ }
    }

    private static void loadSave() {
        try {
            if (Files.exists(SAVE_FILE)) {
                String s = Files.readString(SAVE_FILE).trim();
                if (s.startsWith("{") && s.endsWith("}")) saveJson = s;
            }
        } catch (IOException ignored) { /* start with an empty save */ }
    }

    private static synchronized void persistSave() {
        try { Files.writeString(SAVE_FILE, saveJson); }
        catch (IOException ignored) { /* progress just will not persist across restarts */ }
    }

    /**
     * The page is assembled at runtime from several text blocks. (A single compile-time constant
     * larger than 64 KB is rejected by javac, so the pieces are joined with String.join.)
     */
    private static String buildPage() {
        return String.join("\n", HTML_HEAD, JS_ENGINE, JS_SIM, JS_AUDIO, JS_HAZARDS, JS_NEW1, JS_NEW2, JS_SCREENS, JS_RENDER, JS_RENDER2, HTML_TAIL);
    }

    // =====================================================================================
    //  CLIENT: HTML shell (16:9 canvas scaled to the window)
    // =====================================================================================

    private static final String HTML_HEAD = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Strike D Lata</title>
<style>
  @font-face{font-family:'Jersey 10';src:url('/f/body.woff2') format('woff2');font-weight:400}
  @font-face{font-family:'Press Start 2P';src:url('/f/title.woff2') format('woff2')}
  html,body{margin:0;height:100%;background:#120d2e;overflow:hidden}
  body{display:flex;align-items:center;justify-content:center}
  canvas{display:block;aspect-ratio:16/9;max-width:100vw;max-height:100vh;width:min(100vw,177.78vh);height:auto;background:#000;outline:none;cursor:default}
</style>
</head>
<body>
<canvas id="game" tabindex="0"></canvas>
<script>
""";

    private static final String HTML_TAIL = """
</script>
</body>
</html>
""";

    private static final String JS_ENGINE = """
(function(){
'use strict';
/* =========================================================================
   STRIKE D LATA v4 - engine part 1: constants, profiles, leaderboard, input, menus
   Canvas = 1280x720 (16:9). The ARENA is the centre 60% (768px, x 256..1024);
   the left/right 20% (256px each) are animated side pillars.
   World coordinates are arena-local: x 0..AW, y 0..H.
   ========================================================================= */
var W=1280,H=720,AW=768,AX=256,K=2.4,TOP=128,BOT=H-20,CAN_X=AW/2,CAN_Y=250;
var cv=document.getElementById('game');
var mainCtx=cv.getContext('2d');
cv.width=W;cv.height=H;
/* layered renderer: art goes to an off-screen layer; text is queued and printed on top of each layer. */
var PXS=0.5,lowCv=document.createElement('canvas');lowCv.width=Math.ceil(W*PXS);lowCv.height=Math.ceil(H*PXS);
var ctx=lowCv.getContext('2d');ctx.imageSmoothingEnabled=true;
var TQ=[];
function present(){
  mainCtx.setTransform(1,0,0,1,0,0);mainCtx.globalAlpha=1;mainCtx.imageSmoothingEnabled=false;
  mainCtx.drawImage(lowCv,0,0,W,H);
  var k=1/PXS,i,q;
  for(i=0;i<TQ.length;i++){
    q=TQ[i];var M=q.M;
    mainCtx.setTransform(M.a*k,M.b*k,M.c*k,M.d*k,M.e*k,M.f*k);mainCtx.globalAlpha=q.a;
    mainCtx.font=q.tf?(q.size+'px '+TFONT):(Math.round(q.size*FSC)+'px '+FONT);mainCtx.textAlign=q.al;mainCtx.textBaseline='middle';mainCtx.lineJoin='round';
    if(q.ol){mainCtx.lineWidth=Math.max(3,q.size/(q.tf?6:6));mainCtx.strokeStyle=INK;mainCtx.strokeText(q.s,q.x,q.y);}
    mainCtx.fillStyle=q.col;mainCtx.fillText(q.s,q.x,q.y);
  }
  mainCtx.setTransform(1,0,0,1,0,0);mainCtx.globalAlpha=1;
  TQ.length=0;
  ctx.save();ctx.setTransform(1,0,0,1,0,0);ctx.globalAlpha=1;ctx.clearRect(0,0,lowCv.width,lowCv.height);ctx.restore();
}

function clamp(v,a,b){return Math.max(a,Math.min(b,v));}
function dist(a,b){return Math.hypot(a.x-b.x,a.y-b.y);}
function rnd(a,b){return a+Math.random()*(b-a);}
function pick(arr){return arr[Math.floor(Math.random()*arr.length)];}
function mulberry(seed){return function(){seed|=0;seed=seed+0x6D2B79F5|0;var t=Math.imul(seed^seed>>>15,1|seed);t=t+Math.imul(t^t>>>7,61|t)^t;return((t^t>>>14)>>>0)/4294967296;};}

var bus={m:{},
  on:function(e,f){(this.m[e]=this.m[e]||[]).push(f);},
  emit:function(e,d){var l=this.m[e];if(l)for(var i=0;i<l.length;i++)l[i](d);}};

/* ---------- tables ---------- */
var BASE_SPEED=82*K,PICKUP_R=34,TAG_R=40,HITTER_R=13,FLIGHT_PEAK=95;
var ENV_NAMES=['KALYE UNO','DALAMPASIGAN','PALAYAN','PERYA SA GABI','BULKANG MAYON'],ENVART=[0,3,4,2,5],NB=5;   // ENVART: biome index -> art id used by the pixel backgrounds / pillars / props
var NLV=50,GATE=[0,12,30,52,76];   // total stars needed (from earlier biomes) to open each biome
var BIOMES=[{name:'KALYE UNO',range:'LEVELS 1-10',col:'#ff8d5c',col2:'#c4412a'},
            {name:'DALAMPASIGAN',range:'LEVELS 11-20',col:'#ffcf5c',col2:'#e08a1e'},
            {name:'PALAYAN',range:'LEVELS 21-30',col:'#6fd66a',col2:'#2b8a4a'},
            {name:'PERYA SA GABI',range:'LEVELS 31-40',col:'#ff5ea8',col2:'#8a2a6a'},
            {name:'BULKANG MAYON',range:'LEVELS 41-50',col:'#ff7a2a',col2:'#7a1e10'},
            {name:'SOON',range:'COMING SOON',col:'#9aa0c8',col2:'#4a4e7a'}];
/* Pogs: either a named pog (known effect) or a "?" mystery pog - touching one (or hitting it with a slipper) rolls a random
   BUFF or a TRAP. */
var POGS={
  GHOST:{name:'GHOST SLIPPER',bg:'#b9a7ff',bg2:'#6f56e8',dur:0,short:'GHOST',
    desc:'Your next throw is a spirit pamato: it flies straight through bikes, bins and pedestrians, and returns to your hand after landing.',tip:'Best for courts with lots of clutter.'},
  SPEED:{name:'SPEED BOOST',bg:'#7ff0ff',bg2:'#1ea7e8',dur:8,short:'SPEED',
    desc:'Run 40% faster for 8 seconds. Outrun the Taya and snatch slippers back before she reaches them.',tip:'Great for a risky retrieval during habulan.'},
  MULTI:{name:'MULTI-SHOT',bg:'#ffe27a',bg2:'#ff9a2e',dur:10,short:'MULTI',
    desc:'For 10 seconds every throw spawns two echo slippers that fan out beside the real one - more chances to hit the can.',tip:'Echoes cost no ammo and vanish when they land.'},
  COINS:{name:'DOUBLE COINS',bg:'#ffd23f',bg2:'#d98a00',dur:15,short:'2X COINS',
    desc:'All field coin pickups are worth double (+20) for 15 seconds.',tip:'Grab it, then sweep the coins in the middle of the field.'},
  SPARE:{name:'SPARE PAMATO',bg:'#9dffb0',bg2:'#1fa84a',dur:0,short:'+1 PAMATO',
    desc:'Instantly gives you one extra pamato, even above your starting supply.',tip:'A lifesaver when you are almost out of slippers.'},
  FREEZE:{name:'TAYA FREEZE',bg:'#d8f4ff',bg2:'#4aa8e8',dur:0,short:'FREEZE',
    desc:'The Taya is frozen stiff and stunned for 3 seconds. Rush in and retrieve your slippers or take a free shot.',tip:'Does nothing against a boss mid-attack, so save it for normal levels.'},
  MUD:{name:'STICKY MUD',bg:'#a07a4a',bg2:'#5a3a1c',dur:5,short:'MUD',trap:true,
    desc:'TRAP! You get stuck in sticky mud and move at 40% speed for 5 seconds.',tip:'Retreat to the safe zone and wait it out.'},
  REVERSE:{name:'DIZZY SPIN',bg:'#ff9fd0',bg2:'#b0307a',dur:6,short:'DIZZY',trap:true,
    desc:'TRAP! Your left/right and up/down controls are swapped for 6 seconds, aim rotation too.',tip:'Move slowly and re-learn your keys.'},
  JITTER:{name:'JITTERY HANDS',bg:'#c9f06a',bg2:'#6a9a1a',dur:7,short:'JITTER',trap:true,
    desc:'TRAP! Your power meter swings 70% faster and the aim reticle shakes for 7 seconds.',tip:'Hold your throw until it wears off.'},
  RAGE:{name:'TAYA RAGE',bg:'#ff6a6a',bg2:'#a01818',dur:6,short:'RAGE',trap:true,
    desc:'TRAP! The Taya flies into a rage and runs 35% faster for 6 seconds - for the whole team.',tip:'Stay in the safe zone until she calms down.'}
};
var POG_ORDER=['GHOST','SPEED','MULTI','COINS','SPARE','FREEZE','MUD','REVERSE','JITTER','RAGE'];
var BUFFS=['GHOST','SPEED','MULTI','COINS','SPARE','FREEZE'],TRAPS=['MUD','REVERSE','JITTER','RAGE'];
/* Pinoy characters: unlock with coins, then buy 3 passive skill nodes each */
var CHARS={
  JUAN:{name:'JUN-JUN',role:'THE STRIKER',title:'Jun-Jun - raw power and precision',cost:0,shirt:'#38b6ff',pants:'#1f3a8a',hair:'#1d1b2e',skin:'#f2b88a',
    bio:'Wears heavy-duty Rambo tsinelas. One throw can ricochet right off a wall and clobber the lata from behind cover.',
    skills:[
      {n:'LAKAS NG BRASO',t:'PASSIVE',d:['Throw velocity +12%','Throw velocity +24%','Throw velocity +36%']},
      {n:'MABIGAT NA PALO',t:'PASSIVE',d:['Lata knockback +20%','Lata knockback +40%','Lata knockback +60%']},
      {n:'RAMBO RICOCHET',t:'ACTIVE',d:['Heavy slipper bounces off walls and cover (CD 16s)','Ricochet (CD 14s)','Ricochet (CD 12s)']},
      {n:'TALBOG',t:'PASSIVE',d:['Ricochet gets +1 bounce','+2 bounces','+3 bounces']}]},
  MARIA:{name:'NENE',role:'THE SPRINTER',title:'Nene - patintero champion',cost:400,shirt:'#ff7fbf',pants:'#5a2a7a',hair:'#2a1408',skin:'#e8b48a',
    bio:'Thin beach tsinelas, quick feet. She snatches fallen slippers right under the nose of Taya.',
    skills:[
      {n:'BILIS',t:'PASSIVE',d:['Sprint speed +10%','Sprint speed +20%','Sprint speed +30%']},
      {n:'PAHINGA',t:'PASSIVE',d:['Cooldowns -2.5s','Cooldowns -5s','Cooldowns -7.5s']},
      {n:'PATINTERO DASH',t:'ACTIVE',d:['Burst of speed + i-frames (CD 16s)','Longer dash (CD 14s)','Longest dash (CD 12s)']},
      {n:'HANGIN',t:'PASSIVE',d:['Taya slowed 8% near her','Taya slowed 16% near her','Taya slowed 24% near her']}]},
  BERTO:{name:'BOYET',role:'THE TACTICIAN',title:'Boyet - gadgets and distractions',cost:700,shirt:'#d83a3a',pants:'#222640',hair:'#1a1008',skin:'#d99467',hat:'band',
    bio:'Pockets full of teks and sumpit tricks. Drops decoys that confuse the Taya and shield friends from disasters.',
    skills:[
      {n:'MALAWAK NA USOK',t:'PASSIVE',d:['Decoy radius +20','Decoy radius +40','Decoy radius +60']},
      {n:'MATAGAL NA TULIG',t:'PASSIVE',d:['Longer Taya stun and decoy','Even longer','Longest']},
      {n:'SUMPIT SMOKE / TEKS DECOY',t:'ACTIVE',d:['Drop teks + smoke that confuses the Taya (CD 14s)','Decoy (CD 12s)','Decoy (CD 10s)']},
      {n:'TIBAY SA SAKUNA',t:'PASSIVE',d:['Disaster slow -25%, mud halved','Disaster slow -50%, wind -25%','No slipping or slow, wind -50%']}]},
  SANTIAGO:{name:'TABA',role:'THE HEAVY',title:'Kuya Bong - slow, durable and disruptive',cost:1000,shirt:'#e8a82a',pants:'#6a2a1a',hair:'#14100c',skin:'#c98458',hat:'salakot',
    bio:'Big, slow and tough as a carabao. His stomp rattles the whole court and clears the debris.',
    skills:[
      {n:'LAPAD NG LINDOL',t:'PASSIVE',d:['Stomp shockwave wider','Much wider','Huge shockwave']},
      {n:'DALAWANG TSINELAS',t:'PASSIVE',d:['Carry 1 extra tsinelas','Carry 1 extra, safe time +0.4s','Carry 2 extra tsinelas']},
      {n:'LINDOL STOMP',t:'ACTIVE',d:['Stun the Taya and smash debris (CD 18s)','Stomp (CD 16s)','Stomp (CD 14s)']},
      {n:'BIGAT NG TAMA',t:'PASSIVE',d:['Heavier lata impact, +10% score','Impact +25%, +20% score','Impact +40%, +30% score']}]}
};
var CHAR_ORDER=['JUAN','MARIA','BERTO','SANTIAGO'];
var SKILL_COST=[120,240,400];
var ACT_KEY=['KeyE','ShiftRight'],ACT_NAME=['E','R-SHIFT'];
/* endless co-op: cumulative unlock thresholds per biome [points, stars] - reach EITHER one */
var SURV_GATE=[[0,0],[1500,10],[4000,28],[8000,52],[14000,80]];
var SKINS={
  TSINELAS:{name:'ORANGE TSINELAS',cost:0,c1:'#ff8a2b',c2:'#ffc16a',strap:'#1d1b2e',trail:'',note:'The classic pamato'},
  PURPLE:{name:'PURPLE SLIDES',cost:300,c1:'#8b4dff',c2:'#c9a6ff',strap:'#ffffff',band:true,trail:'purple',note:'Violet spark trail'},
  FLOWER:{name:'FLOWER CLOGS',cost:600,c1:'#ffd9e8',c2:'#fff4f8',strap:'#ff6fae',flowers:true,trail:'petal',note:'Petal trail'},
  BAYBAYIN:{name:'BAYBAYIN SLIDES',cost:900,c1:'#e8d2a0',c2:'#fbf0d0',strap:'#6a3a14',baybayin:true,trail:'glyph',note:'Ancient script glyph trail'},
  GOLD:{name:'GOLDEN TSINELAS',cost:1200,c1:'#ffc928',c2:'#fff09a',strap:'#9a6a00',trail:'gold',note:'Golden star trail'},
  JEEPNEY:{name:'JEEPNEY SLIPPERS',cost:1500,c1:'#d6dde8',c2:'#ffffff',strap:'#e0262e',jeep:true,trail:'jeep',note:'Colorful jeepney streak trail'},
  APOY:{name:'APOY SLIPPER',cost:0,tan:12,c1:'#ff5a1f',c2:'#ffd23f',strap:'#3a0e08',trail:'fire',apoy:true,note:'Burns away puddles and ash'},
  KIDLAT:{name:'KIDLAT SLIPPER',cost:0,tan:20,c1:'#3b6bff',c2:'#bfe6ff',strap:'#fff3a0',band:true,trail:'purple',note:'Storm-blue spark trail'},
  BAKUNAWA:{name:'BAKUNAWA SLIPPER',cost:0,tan:30,c1:'#17342f',c2:'#43e0a8',strap:'#ffd23f',trail:'gold',note:'Moon-eater green glow'}
};
var SKIN_ORDER=['TSINELAS','PURPLE','FLOWER','BAYBAYIN','GOLD','JEEPNEY','APOY','KIDLAT','BAKUNAWA'];
var UPS={
  sprint:{name:'SPRINT SPEED',desc:'Run 8% faster per level',costs:[150,300,500]},
  heavy:{name:'HEAVIER PAMATO',desc:'25% less wind deflection per level',costs:[150,300,500]},
  aim:{name:'EXTENDED AIM LINE',desc:'Longer aiming guide per level',costs:[150,300,500]},
  slow:{name:'SLOWER SWING METER',desc:'Meter swings 12% slower per level',costs:[150,300,500]}
};
var UP_ORDER=['sprint','heavy','aim','slow'];
var PAUSE_BTN={x:AX+AW-62,y:8,w:50,h:50};

/* ---------- global state ---------- */
var LEVELS=[],TIPS={},DB={profiles:[],active:''},SAVE=null,ALL_UNLOCKED=false,BOARD=[];
var scene='LOADING';
var PROF=null,G=null,players=2,anim=0,shake=0,muted=false,musicOn=true,sel=0,mouse={x:-1,y:-1},lastKey='';
var shopTab=0,selLevel=1,biome=0,slideX=0,shopFrom='MENU',shopMsg='',shopMsgT=0,selMsg='',selMsgT=0;
var tipIdx={};
var bgCanvas=null,bgKey=-1,loadError='';
var pSel=0,pOff=0,delConfirm=false,nameBuf='',nameMode='NEW',nameErr='',profMsg='',profMsgT=0;
var rulesTab=0,pogSel=0,disSel=0,charSel=0,charSlot=0,survSel=0,RUN=null;

/* ---------- profiles & persistence ---------- */
function zeros(){var z=[];for(var i=0;i<NLV;i++)z.push(0);return z;}
function defaultSave(){return {name:'',coins:0,tansan:0,stars:zeros(),cleared:zeros(),hi:zeros(),owned:['TSINELAS'],skin:'TSINELAS',up:{sprint:0,heavy:0,aim:0,slow:0},chars:['JUAN'],sel:['JUAN','JUAN'],sk:{JUAN:[0,0,0,0],MARIA:[0,0,0,0],BERTO:[0,0,0,0],SANTIAGO:[0,0,0,0]},pts:0,es:0,eb:[0,0,0,0,0,0],ebs:[0,0,0,0,0,0]};}
function normSave(s){
  var d=defaultSave();
  if(!s||typeof s!=='object')return d;
  function arr(a){var o=zeros();if(Array.isArray(a))for(var i=0;i<NLV;i++)o[i]=Math.max(0,Number(a[i])||0);return o;}
  d.name=cleanName(String(s.name||''));
  d.coins=Math.max(0,Math.floor(Number(s.coins)||0));d.tansan=Math.max(0,Math.floor(Number(s.tansan)||0));
  d.stars=arr(s.stars);d.cleared=arr(s.cleared);d.hi=arr(s.hi);
  if(Array.isArray(s.owned)){d.owned=s.owned.filter(function(k){return SKINS[k];});if(d.owned.indexOf('TSINELAS')<0)d.owned.unshift('TSINELAS');}
  d.skin=(SKINS[s.skin]&&d.owned.indexOf(s.skin)>=0)?s.skin:'TSINELAS';
  if(s.up)for(var k in d.up)d.up[k]=clamp(Math.floor(Number(s.up[k])||0),0,3);
  if(Array.isArray(s.chars)){d.chars=s.chars.filter(function(k){return CHARS[k];});if(d.chars.indexOf('JUAN')<0)d.chars.unshift('JUAN');}
  if(Array.isArray(s.sel))for(var q=0;q<2;q++)d.sel[q]=(CHARS[s.sel[q]]&&d.chars.indexOf(s.sel[q])>=0)?s.sel[q]:'JUAN';
  if(s.sk)for(var c in d.sk)if(Array.isArray(s.sk[c]))for(var u=0;u<4;u++)d.sk[c][u]=clamp(Math.floor(Number(s.sk[c][u])||0),0,3);
  d.pts=Math.max(0,Math.floor(Number(s.pts)||0));d.es=Math.max(0,Math.floor(Number(s.es)||0));
  for(var e=0;e<6;e++){d.eb[e]=Math.max(0,Math.floor(Number(s.eb&&s.eb[e])||0));d.ebs[e]=Math.max(0,Math.floor(Number(s.ebs&&s.ebs[e])||0));}
  return d;
}
function cleanName(s){
  var ok='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 _-',o='',i,c;
  for(i=0;i<s.length&&o.length<12;i++){c=s.charAt(i);if(ok.indexOf(c)>=0)o+=c;}
  return o.trim();
}
/* Every profile owns TWO completely separate saves: d1 = 1-Player Solo, d2 = 2-Player Co-Op
   (levels, stars, clears, coins, skins, upgrades, characters). SAVE always points at the active mode's save. */
function normDB(j){
  var d={profiles:[],active:''},i;
  if(j&&Array.isArray(j.profiles)){
    for(i=0;i<j.profiles.length&&i<12;i++){
      var q=j.profiles[i]||{},nm=cleanName(String(q.name||''));
      if(!nm||findProf(nm,d))continue;
      var pr=(q.d1||q.d2)?{name:nm,d1:normSave(q.d1),d2:normSave(q.d2)}:{name:nm,d1:normSave(q),d2:normSave(null)};  // older flat profile -> its Solo save
      pr.d1.name=nm;pr.d2.name=nm;d.profiles.push(pr);
    }
  }else if(j&&j.stars){var m=normSave(j);d.profiles.push({name:'PLAYER1',d1:m,d2:normSave(null)});d.profiles[0].d1.name='PLAYER1';d.profiles[0].d2.name='PLAYER1';}
  d.active=j&&j.active?String(j.active):'';
  return d;
}
function findProf(n,db){
  db=db||DB;
  for(var i=0;i<db.profiles.length;i++)if(db.profiles[i].name.toLowerCase()===String(n).toLowerCase())return db.profiles[i];
  return null;
}
function saveGame(){
  if(!PROF)return;
  DB.active=SAVE?SAVE.name:DB.active;
  var js=JSON.stringify(DB);
  try{localStorage.setItem('sdl_db_v4',js);}catch(e){}
  try{fetch('/api/save',{method:'POST',body:js}).catch(function(){});}catch(e){}
}
function dats(p){return p.d1?[p.d1,p.d2]:[p];}
function applyMode(){if(PROF)SAVE=players===2?PROF.d2:PROF.d1;}
function setPlayers(n){players=n;applyMode();}
function totalScore(p){var s=0;dats(p).forEach(function(q){for(var i=0;i<NLV;i++)s+=q.hi[i];s+=q.pts||0;});return s;}
function totalStars(p){p=p||SAVE;var s=0;dats(p).forEach(function(q){for(var i=0;i<NLV;i++)s+=q.stars[i];s+=q.es||0;});return s;}
function biomeStars(p,b){var s=0;for(var i=b*10;i<b*10+10&&i<NLV;i++)s+=p.stars[i];return s;}
function starsBefore(b){var s=0;for(var i=0;i<b;i++)s+=biomeStars(SAVE,i);return s;}
function biomeOpen(b){return ALL_UNLOCKED||b===0||starsBefore(b)>=GATE[b];}
function survPts(){return SAVE.pts||0;}
function survStars(){var s=SAVE.es||0;for(var i=0;i<NLV;i++)s+=SAVE.stars[i];return s;}
function survOpen(b){var g=SURV_GATE[b];return ALL_UNLOCKED||b===0||survPts()>=g[0]||survStars()>=g[1];}
function topLevel(p){var n=0;dats(p).forEach(function(q){for(var i=0;i<NLV;i++)if(q.cleared[i]&&i+1>n)n=i+1;});return n;}
function loadBoard(){
  try{fetch('/api/leaderboard').then(function(r){return r.json();}).then(function(b){if(Array.isArray(b))BOARD=b;}).catch(function(){});}catch(e){}
}
function postBoard(p,remove){
  var body=remove?{name:p,remove:true}:{name:p.name,score:totalScore(p),stars:totalStars(p),level:topLevel(p)};
  try{fetch('/api/leaderboard',{method:'POST',body:JSON.stringify(body)}).then(function(r){return r.json();}).then(function(b){if(Array.isArray(b))BOARD=b;}).catch(function(){});}catch(e){}
}
function unlocked(n){if(ALL_UNLOCKED||n===1)return true;var b=Math.floor((n-1)/10);if(!biomeOpen(b))return false;return n%10===1||SAVE.cleared[n-2]===1;}
function skin(){return SKINS[SAVE.skin]||SKINS.TSINELAS;}
function profSay(m){profMsg=m;profMsgT=2.6;}
function chooseProfile(p){PROF=p;applyMode();DB.active=p.name;saveGame();scene='MENU';sel=0;loadBoard();SFX.start();}
function createProfile(name){
  name=cleanName(name);
  if(name.length<2)return 'Name needs at least 2 characters.';
  if(findProf(name))return 'That name is already taken.';
  if(DB.profiles.length>=12)return 'Profile limit reached (12). Delete one first.';
  var p={name:name,d1:defaultSave(),d2:defaultSave()};p.d1.name=name;p.d2.name=name;DB.profiles.push(p);pSel=DB.profiles.length-1;
  saveGame();postBoard(p);return '';
}
function renameProfile(p,name){
  name=cleanName(name);
  if(name.length<2)return 'Name needs at least 2 characters.';
  var o=findProf(name);if(o&&o!==p)return 'That name is already taken.';
  var old=p.name;p.name=name;p.d1.name=name;p.d2.name=name;
  if(old!==name){postBoard(old,true);postBoard(p);}
  saveGame();return '';
}
function deleteProfile(p){
  var i=DB.profiles.indexOf(p);if(i<0)return;
  DB.profiles.splice(i,1);postBoard(p.name,true);
  if(PROF===p){PROF=null;SAVE=normSave(null);DB.active='';}
  pSel=clamp(pSel,0,Math.max(0,DB.profiles.length-1));
  saveGame();
}

/* ---------- keyboard + mouse listeners ---------- */
var keys=Object.create(null);
var BLOCK={Space:1,ArrowUp:1,ArrowDown:1,ArrowLeft:1,ArrowRight:1,Backspace:1,Tab:1};
window.addEventListener('keydown',function(e){
  if(BLOCK[e.code])e.preventDefault();
  if(e.repeat)return;
  keys[e.code]=true;lastKey=e.key;
  bus.emit('key',e.code);
});
window.addEventListener('keyup',function(e){keys[e.code]=false;});
window.addEventListener('blur',function(){
  for(var k in keys)keys[k]=false;
  if(scene==='PLAY')openPause();
});
function mpos(e){var r=cv.getBoundingClientRect();return {x:(e.clientX-r.left)*W/r.width,y:(e.clientY-r.top)*H/r.height};}
function inRect(p,b){return p.x>=b.x&&p.x<=b.x+b.w&&p.y>=b.y&&p.y<=b.y+b.h;}
cv.addEventListener('mousemove',function(e){
  mouse=mpos(e);
  var bs=buttonsFor(scene);
  for(var i=0;i<bs.length;i++)if(inRect(mouse,bs[i])){sel=i;if(bs[i].lv)selLevel=bs[i].lv;}
});
cv.addEventListener('mousedown',function(e){
  mouse=mpos(e);
  if(scene==='PLAY'&&inRect(mouse,PAUSE_BTN)){openPause();return;}
  var bs=buttonsFor(scene);
  for(var i=0;i<bs.length;i++)if(inRect(mouse,bs[i])){sel=i;if(bs[i].lv)selLevel=bs[i].lv;SFX.click();bs[i].act();return;}
});
cv.addEventListener('wheel',function(e){
  if(scene==='PROFILES'){pOff=clamp(pOff+(e.deltaY>0?1:-1),0,Math.max(0,DB.profiles.length-5));e.preventDefault();}
},{passive:false});

/* ---------- scene transitions ---------- */
function openPause(){
  if(!G)return;
  for(var i=0;i<G.hitters.length;i++)G.hitters[i].meter.on=false;
  scene='PAUSE';sel=0;
}
function openBoard(){loadBoard();scene='BOARD';sel=0;}
function needProf(){
  if(PROF)return false;
  scene='PROFILES';sel=0;delConfirm=false;profSay('Choose your profile first!');
  if(DB.profiles.length===0)startName('NEW');
  else{for(var i=0;i<DB.profiles.length;i++)if(DB.profiles[i].name===DB.active)pSel=i;pOff=Math.max(0,pSel-4);}
  return true;
}
function openSelect(){if(needProf())return;if(players===2){scene='SURVIVAL';sel=0;return;}scene='SELECT';sel=0;biome=Math.floor((selLevel-1)/10);slideX=biome*W;}
function openShop(from){if(needProf())return;shopFrom=from;scene='SHOP';sel=0;shopMsg='';}
function shopBack(){
  saveGame();
  if(shopFrom==='PAUSE'&&G)scene='PAUSE';
  else if(shopFrom==='SELECT')openSelect();
  else scene='MENU';
  sel=0;
}
function restartLevel(){saveGame();if(G.endless){startRun(RUN.env);return;}startLevel(G.level);SFX.start();}
function exitToMenu(){saveGame();scene='MENU';sel=0;loadBoard();}
function selectBiome(b){biome=clamp(b,0,NB);var f=Math.min(biome,NB-1)*10+1;if(biome<NB&&Math.floor((selLevel-1)/10)!==biome)selLevel=f;}
function playLevel(n){
  if(!unlocked(n)){
    var bb=Math.floor((n-1)/10);
    selMsg=!biomeOpen(bb)?'Locked! Earn '+GATE[bb]+' stars in earlier biomes ('+starsBefore(bb)+' so far).':'Clear Level '+(n-1)+' first!';
    selMsgT=2.2;SFX.nope();return;
  }
  startLevel(n);SFX.start();
}
function startName(mode){
  if(mode==='RENAME'&&!DB.profiles[pSel])return;
  nameMode=mode;nameBuf=mode==='RENAME'?DB.profiles[pSel].name:'';nameErr='';scene='NAME';sel=0;
}
function submitName(){
  var err=nameMode==='NEW'?createProfile(nameBuf):renameProfile(DB.profiles[pSel],nameBuf);
  if(err){nameErr=err;SFX.nope();return;}
  profSay(nameMode==='NEW'?'Profile created!':'Profile renamed!');scene='PROFILES';sel=0;SFX.buy();
}

/* ---------- menus (buttons shared by mouse and keyboard) ---------- */
function boxRect(n){
  var b=Math.floor((n-1)/10),i=(n-1)%10,col=i%5,row=Math.floor(i/5);
  return {x:167+col*194+(b*W-slideX),y:250+row*174,w:170,h:150};
}
function buttonsFor(sc){
  var cx=W/2;
  function B(label,x,y,w,h,act,on){return {label:label,x:x,y:y,w:w,h:h,act:act,on:on};}
  function IB(icon,label,x,act){return {icon:icon,label:label,x:x,y:430,w:78,h:78,act:act,on:false};}
  var out=[],i;
  if(sc==='MENU')return menuButtons();
  if(sc==='MODE')return modeButtons();
  if(sc==='STORY'||sc==='HOWTO'||sc==='OUTRO')return introButtons(sc);
  if(0)return [IB(musicOn?'music':'musicoff',musicOn?'MUSIC: ON':'MUSIC: OFF',942,function(){musicOn=!musicOn;})];
  if(sc==='BOARD')return [B('BACK',W/2-120,654,240,52,function(){scene='MENU';sel=0;},false)];
  if(sc==='PROFILES'){
    var n=DB.profiles.length,vis=Math.min(5,n-pOff);
    for(i=0;i<vis;i++)(function(ix){
      var o=B(DB.profiles[ix].name,250,170+(ix-pOff)*66,560,58,function(){if(pSel===ix){chooseProfile(DB.profiles[ix]);}else{pSel=ix;delConfirm=false;}},pSel===ix);
      o.prof=ix;out.push(o);
    })(pOff+i);
    if(delConfirm&&DB.profiles[pSel]){
      out.push(B('YES, DELETE',250,520,270,56,function(){deleteProfile(DB.profiles[pSel]);delConfirm=false;profSay('Profile deleted.');},false));
      out.push(B('CANCEL',540,520,270,56,function(){delConfirm=false;},false));
    }else{
      var has=!!DB.profiles[pSel];
      out.push(B('SELECT & PLAY',250,520,270,56,function(){if(has)chooseProfile(DB.profiles[pSel]);},false));
      out.push(B('NEW',540,520,130,56,function(){startName('NEW');},false));
      out.push(B('RENAME',680,520,130,56,function(){startName('RENAME');},false));
      out.push(B('DELETE',250,590,270,52,function(){if(has)delConfirm=true;},false));
      out.push(B('BACK',540,590,270,52,function(){scene='MENU';sel=0;},false));
    }
    return out;
  }
  if(sc==='NAME')return [
    B('OK',cx-260,470,240,56,submitName,false),
    B('CANCEL',cx+20,470,240,56,function(){scene='PROFILES';sel=0;},false)];
  if(sc==='RULES'){
    var tn=['CONTROLS','SCORING','2-PLAYER'];
    for(i=0;i<3;i++)(function(t){out.push(B(tn[t],100+t*270,100,250,50,function(){rulesTab=t;},rulesTab===t));})(i);
    out.push(B('BACK',cx-120,652,240,52,function(){scene='MENU';sel=0;},false));
    return out;
  }
  if(sc==='CAT_POG'){
    for(i=0;i<POG_ORDER.length;i++)(function(t){var o=B(POGS[POG_ORDER[t]].name,60,138+t*50,420,46,function(){pogSel=t;},pogSel===t);o.cat=t;out.push(o);})(i);
    out.push(B('BACK',cx-120,652,240,52,function(){scene='MENU';sel=0;},false));
    return out;
  }
  if(sc==='CAT_DIS'){
    for(i=0;i<HAZ_ORDER.length;i++)(function(t){var o=B(HAZ_ORDER[t],50,150+t*76,330,58,function(){disSel=t;},disSel===t);o.cat=t;out.push(o);})(i);
    out.push(B('BACK',cx-120,652,240,52,function(){scene='MENU';sel=0;},false));
    return out;
  }
  if(sc==='CHARS'){
    for(i=0;i<4;i++)(function(t){var o=B(CHARS[CHAR_ORDER[t]].name,60,150+t*100,380,86,function(){charSel=t;},charSel===t);o.cat=t;out.push(o);})(i);
    var cid=CHAR_ORDER[charSel],own=SAVE.chars.indexOf(cid)>=0;
    for(i=0;i<4;i++)(function(k){var o=B('SKILL '+(k+1),486+k*183,352,175,170,function(){buySkill(cid,k);},false);o.node=k;out.push(o);})(i);
    out.push(B(own?(SAVE.sel[charSlot]===cid?'IN USE':'USE'):'UNLOCK '+CHARS[cid].cost,486,556,300,52,function(){buyChar(cid);},false));
    out.push(B('FOR: P'+(charSlot+1),806,556,200,52,function(){charSlot=1-charSlot;},false));
    out.push(B('BACK',cx-120,652,240,52,function(){saveGame();scene='MENU';sel=0;},false));
    return out;
  }
  if(sc==='SURVIVAL'){
    for(i=0;i<NB;i++)(function(b){var o=B(BIOMES[b].name,cx-439+b*177,100,170,50,function(){survSel=b;},survSel===b);o.sb=b;out.push(o);})(i);
    out.push(B('START ENDLESS RUN',cx-190,566,380,64,function(){startRun(survSel);},false));
    out.push(B('BACK',48,636,200,52,function(){scene='MENU';sel=0;},false));
    return out;
  }
  if(sc==='UPGRADE'){
    for(i=0;i<3;i++)(function(k){var o=B('UPGRADE '+(k+1),180+k*310,210,290,300,function(){pickUpgrade(k);},false);o.up=k;out.push(o);})(i);
    out.push(B('NEXT WAVE',W/2-150,560,300,60,function(){nextWave();},false));
    return out;
  }
  if(sc==='SELECT'){
    for(i=0;i<=NB;i++)(function(b){var o=B(BIOMES[b].name,cx-459+b*153,92,146,50,function(){selectBiome(b);},biome===b);o.tab=b;out.push(o);})(i);
    out.push(B('<',48,370,66,110,function(){selectBiome(biome-1);},false));
    out.push(B('>',W-114,370,66,110,function(){selectBiome(biome+1);},false));
    for(i=1;i<=NLV;i++)(function(n){var r=boxRect(n);var o=B('L'+n,r.x,r.y,r.w,r.h,function(){selLevel=n;playLevel(n);},false);o.lv=n;if(Math.floor((n-1)/10)===biome&&biome<NB)out.push(o);})(i);
    out.push(B('BACK',48,636,200,56,function(){scene='MENU';sel=0;},false));
    out.push(B('SHOP',W-248,636,200,56,function(){openShop('SELECT');},false));
    return out;
  }
  if(sc==='SHOP'){
    var rs=shopRects();
    for(i=0;i<rs.length;i++)(function(r){var o=B(r.key,r.x,r.y,r.w,r.h,r.act,false);o.kind=r.kind;o.key=r.key;out.push(o);})(rs[i]);
    out.push(B('BACK',cx-120,652,240,52,shopBack,false));
    return out;
  }
  var bw=430,bh=56,ax=AX+AW/2;
  if(sc==='PAUSE')return [
    B('RESUME',ax-bw/2,236,bw,bh,function(){scene='PLAY';},false),
    B(G.endless?'RESTART RUN':'RESTART LEVEL',ax-bw/2,306,bw,bh,restartLevel,false),
    B('TSINELAS SHOP',ax-bw/2,376,bw,bh,function(){openShop('PAUSE');},false),
    B(G.endless?'CHANGE BIOME':'LEVEL SELECT',ax-bw/2,446,bw,bh,function(){saveGame();selLevel=G.level;openSelect();},false),
    B('EXIT TO MAIN MENU',ax-bw/2,516,bw,bh,exitToMenu,false)];
  if(sc==='BRIEF')return [B('START LEVEL  (ENTER / SPACE)',ax-bw/2-30,566,bw+60,58,function(){if(G.brief&&G.brief.age>0.5)ackBrief();},false)];
  if(sc==='LEVEL_FAILED')return [
    B(G.endless?'NEW RUN':'RETRY LEVEL',ax-bw/2,456,bw,bh,restartLevel,false),
    B(G.endless?'CHANGE BIOME':'LEVEL SELECT',ax-bw/2,526,bw,bh,function(){saveGame();selLevel=G.level;openSelect();},false),
    B('MAIN MENU',ax-bw/2,596,bw,bh,exitToMenu,false)];
  if(sc==='LEVEL_CLEAR'){
    var l=[],nx=G.level<NLV,bw2=232,x0=nx?ax-bw2*1.5-10:ax-bw2-5;
    if(nx)l.push(B('NEXT LEVEL',x0,600,bw2,56,function(){var n=G.level+1,bb=Math.floor((n-1)/10);selLevel=n;if(!biomeOpen(bb)){openSelect();selMsg='Locked! Earn '+GATE[bb]+' stars in earlier biomes ('+starsBefore(bb)+' so far).';selMsgT=3;return;}startLevel(n);},false));
    l.push(B('REPLAY',x0+(nx?bw2+10:0),600,bw2,56,restartLevel,false));
    l.push(B('LEVELS',x0+(nx?2*bw2+20:bw2+10),600,bw2,56,function(){selLevel=G.level<NLV?G.level+1:NLV;openSelect();},false));
    return l;
  }
  return [];
}
function shopRects(){
  var r=[],i;
  var sx=[50,247,444,50,247,444],sy=[190,190,190,415,415,415];
  r.push({kind:'tab',key:'TAB',x:360,y:124,w:274,h:40,act:function(){shopTab=1-shopTab;SFX.click();}});
  for(i=0;i<(shopTab?3:6);i++)(function(id,x,y){r.push({kind:'skin',key:id,x:x,y:y,w:190,h:215,act:function(){buySkin(id);}});})(SKIN_ORDER[i+(shopTab?6:0)],sx[i],sy[i]);
  for(i=0;i<4;i++)(function(id,y){r.push({kind:'up',key:id,x:660,y:y,w:560,h:100,act:function(){buyUp(id);}});})(UP_ORDER[i],190+i*112);
  return r;
}
function shopSay(m){shopMsg=m;shopMsgT=2.4;}
function buySkin(id){
  var s=SKINS[id];
  if(SAVE.owned.indexOf(id)>=0){SAVE.skin=id;shopSay('Equipped '+s.name);SFX.click();saveGame();return;}
  if(s.tan){
    if((SAVE.tansan||0)<s.tan){shopSay('Need '+(s.tan-(SAVE.tansan||0))+' more Tansan! Find them during disasters.');SFX.nope();return;}
    SAVE.tansan-=s.tan;SAVE.owned.push(id);SAVE.skin=id;shopSay('Unlocked '+s.name+'!');SFX.buy();saveGame();return;
  }
  if(SAVE.coins<s.cost){shopSay('Not enough coins! Need '+(s.cost-SAVE.coins)+' more.');SFX.nope();return;}
  SAVE.coins-=s.cost;SAVE.owned.push(id);SAVE.skin=id;shopSay('Bought '+s.name+'!');SFX.buy();saveGame();
}
function buyUp(id){
  var u=UPS[id],lv=SAVE.up[id];
  if(lv>=3){shopSay(u.name+' is already maxed.');SFX.nope();return;}
  var c=u.costs[lv];
  if(SAVE.coins<c){shopSay('Not enough coins! Need '+(c-SAVE.coins)+' more.');SFX.nope();return;}
  SAVE.coins-=c;SAVE.up[id]=lv+1;shopSay(u.name+' upgraded to level '+(lv+1)+'!');SFX.buy();saveGame();
}

function buyChar(id){
  var c=CHARS[id];
  if(SAVE.chars.indexOf(id)>=0){SAVE.sel[charSlot]=id;SFX.click();saveGame();return;}
  if(SAVE.coins<c.cost){shopSay('Not enough coins! Need '+(c.cost-SAVE.coins)+' more.');SFX.nope();return;}
  SAVE.coins-=c.cost;SAVE.chars.push(id);SAVE.sel[charSlot]=id;shopSay(c.name+' unlocked!');SFX.buy();saveGame();
}
function buySkill(id,k){
  if(SAVE.chars.indexOf(id)<0){shopSay('Unlock '+CHARS[id].name+' first.');SFX.nope();return;}
  var lv=SAVE.sk[id][k];
  if(lv>=3){shopSay('Skill already maxed.');SFX.nope();return;}
  var cost=SKILL_COST[lv]+(CHARS[id].skills[k].t==='ACTIVE'?60:0);
  if(SAVE.coins<cost){shopSay('Not enough coins! Need '+(cost-SAVE.coins)+' more.');SFX.nope();return;}
  SAVE.coins-=cost;SAVE.sk[id][k]=lv+1;shopSay(CHARS[id].skills[k].n+' level '+(lv+1)+'!');SFX.buy();saveGame();
}

/* =========================================================================
   engine part 2: level setup, manual aim + power meter, pogs, coins
   ========================================================================= */
var AIM_ROT=1.4,AIM_MAX=1.25;
function mkHitter(i){
  var one=G.players===1,am=G.cfg.ammo;
  return {id:i,name:i?'P2':'P1',col:i?'#ffc533':'#38b6ff',x:one?AW/2:AW/2-110+i*220,y:650,face:1,moving:false,vx:0,vy:0,
    ammo:one?3:am+(G.endless?RU('ammo'):0),total:one?3:am+(G.endless?RU('ammo'):0),acd:0,acdMax:1,palo:0,meter:{on:false,p:0,m:0},aim:0,char:SAVE.sel[i]||'JUAN',svx:0,svy:0,
    inv:0,boost:0,multi:0,dbl:0,mud:0,rev:0,jit:0,ghost:false,last:0,throwAnim:0,stun:0,score:0,syncT:-99};
}
/* ---------- character skills (4 per hero: 3 passive + 1 ACTIVE) and endless run upgrades ---------- */
function sl(h,id,k){var c=SAVE.sk[id];return (h.char===id&&c&&c[k])||0;}
function RU(id){return (RUN&&G&&G.endless&&RUN.up[id])||0;}
function teamSl(id,k){var m=0;if(!G)return 0;for(var i=0;i<G.hitters.length;i++)m=Math.max(m,sl(G.hitters[i],id,k));return m;}
function chSpeed(h){return 1+0.10*sl(h,'MARIA',0)+(h.char==='MARIA'?0.08:0)-(h.char==='SANTIAGO'?0.1:0)+0.08*RU('spd');}
function chWind(h){return 0.1*sl(h,'BERTO',3)+0.2*RU('wind');}
function chPerfect(h){return 0.1+0.025*RU('perf')+(h.char==='JUAN'?0.02:0);}
function chPick(h){return 1+0.2*RU('pick')+(h.dash>0?1.6:0)+(h.char==='SANTIAGO'?0.15:0);}
function chMeter(h){return Math.max(0.45,1-0.08*RU('meter'));}
function chAim(h){return 0;}
function chInv(h){return 2.6+0.4*sl(h,'SANTIAGO',1)*(sl(h,'SANTIAGO',1)>=2?1:0)+0.8*RU('inv');}
function chCoin(h){return 1+0.4*RU('coin');}
function chScore(h){return 1+0.1*sl(h,'SANTIAGO',3);}
function luckBuff(){return 0.62;}
function lifeBonus(){var m=0;for(var p=0;p<2;p++){if(SAVE.sel[p]==='SANTIAGO')m=1;}return m;}
function carryBonus(h){return h.char==='SANTIAGO'?[0,1,1,2][sl(h,'SANTIAGO',1)]:0;}
function actCd(h,lv){return Math.max(5,ACT_CD[h.char][lv-1]-(h.char==='MARIA'?2.5*sl(h,'MARIA',1):0));}
function inSmoke(e){var d=G&&G.decoy;return !!(d&&d.t>0&&Math.hypot(e.x-d.x,(e.y-d.y)*1.3)<d.r);}
var ACT_CD={JUAN:[16,14,12],MARIA:[20,18,16],BERTO:[14,12,10],SANTIAGO:[18,16,14]};
function actLevel(h){return sl(h,h.char,2);}
function useActive(i){
  if(scene!=='PLAY'||!G)return;
  var h=G.hitters[i];if(!h)return;
  var lv=actLevel(h),sk=CHARS[h.char].skills[2];
  if(lv<1){toast(h.name+': learn '+sk.n+' in CHARACTERS','#ff9a8a');SFX.nope();return;}
  if(h.acd>0){toast(h.name+': '+sk.n+' ready in '+Math.ceil(h.acd)+'s','#ff9a8a');return;}
  if(h.stun>0)return;
  h.acd=actCd(h,lv);h.acdMax=h.acd;
  if(h.char==='JUAN'){h.rico=1;pop(h.x,h.y-100,'RAMBO RICOCHET READY!','#ff9a3a');popBang(h.x,h.y-70,'RAMBO!','#ff9a3a');burst(h.x,h.y-30,'#ff9a3a',18,90);}
  else if(h.char==='MARIA'){h.dash=0.6+0.2*lv;h.inv=Math.max(h.inv,h.dash+0.25);dust(h.x,h.y,8);popBang(h.x,h.y-90,'WHOOSH!','#9fe9ff');}
  else if(h.char==='BERTO'){
    var sd=sl(h,'BERTO',0),st=sl(h,'BERTO',1);
    G.decoy={x:h.x,y:Math.min(h.y-40,G.line-30),r:70+20*sd+10*lv,t:6+1.5*st+lv,stun:1.3+0.5*st+0.3*lv,ph:Math.random()*6};
    popBang(h.x,h.y-90,'POOF!','#cfd6e6');dust(G.decoy.x,G.decoy.y,12,'#d8dce8');
  }
  else{
    var rad=130+32*sl(h,'SANTIAGO',0)+12*lv,T2=G.taya,sdur=1.1+0.4*lv,j,n=0;
    shockwave(h.x,h.y,rad);shake=Math.max(shake,18);SFX.rumble&&SFX.rumble();
    if(Math.hypot(T2.x-h.x,(T2.y-h.y)*1.2)<rad){T2.state='STUN';T2.stun=sdur;T2.zap=sdur;if(G.can.carried)dropCan();pop(T2.x,T2.y-110,'STUNNED!','#ffe27a');}
    for(j=G.obst.length-1;j>=0;j--){var ob=G.obst[j];if((ob.type==='rock'||ob.type==='trash'||ob.type==='bin'||ob.ttl)&&Math.hypot(ob.x-h.x,(ob.y-h.y)*1.2)<rad+30){burst(ob.x,ob.y,'#a07a4a',10,90);G.obst.splice(j,1);n++;}}
    popBang(h.x,h.y-90,'LINDOL!','#ffb347');if(n)pop(h.x,h.y-130,'DEBRIS CLEARED x'+n,'#ffe27a');
  }
  SFX.pog();
}
function startLevel(n,cfgO){
  n=clamp(n,1,NLV);applyMode();
  var cfg=cfgO||LEVELS[n-1],two=players===2,endl=!!cfgO&&!!RUN;
  G={players:players,two:two,level:n,cfg:cfg,score:0,mult:1,streak:0,bestStreak:0,lives:endl?RUN.lives:(two?4:3)+(SAVE.sel[0]==='SANTIAGO'?1:0),endless:endl,rage:0,time:0,lt:0,timeLeft:cfg.timeLimit,
     livesLost:0,pamatoLost:0,perfectRetr:0,coinsLvl:0,hits:0,need:cfg.hits+(two?1:0),goal:two?cfg.targetScore2p:cfg.targetScore,
     reactMul:two?0.7:1,spdMul:two?1.1:1,syncs:0,clearing:false,clearT:0,line:cfg.lineY,failReason:''};
  G.can={x:CAN_X,y:CAN_Y,state:'up',fall:0,phase:'rest',vx:0,vy:0,z:0,vz:0,rot:0,rv:0,carried:false,setP:0};
  if(endl)G.endless=true;
  G.hitters=[mkHitter(0)];
  if(two)G.hitters.push(mkHitter(1));
  G.slippers=[];G.pogs=[];G.parts=[];G.pops=[];G.toasts=[];G.coins=[];G.zones=[];G.strikes=[];
  var f=genField(cfg);G.obst=f.obst;G.pud=f.pud;
  G.taya={x:CAN_X,y:CAN_Y+70,state:'GUARD',t:0,react:0,stun:0,face:-1,side:0,sideDir:1,stuck:0,maxY:cfg.lineY-22};
  G.haz=null;G.hazTimer=rnd(cfg.hazMin,cfg.hazMax)+4;G.hazCount=0;G.lastHaz='';
  G.wx=0;G.wy=0;G.gx=0;G.gy=0;G.twx=0;G.twy=0;G.gustPh=Math.random()*6;
  G.pogTimer=rnd(9,14);G.coinTimer=rnd(1.5,3);
  G.phase='STANDOFF';G.stats={tags:0};G.chase=false;
  G.banner=endl?{text:'WAVE '+RUN.wave,sub:ENV_NAMES[cfg.env]+'  -  ENDLESS CO-OP',t:2.8}:{text:'LEVEL '+n,sub:ENV_NAMES[cfg.env]+(two?'  -  2-PLAYER CO-OP':'  -  SOLO'),t:2.8};
  scene='PLAY';bgKey=-1;
  var fe=endl?RUN.wave===1:((n-1)%10===0);
  if(fe){                              // first level of a biome: introduce its new disaster(s) BEFORE play starts
    var nl=DIS_BY_BIOME[cfg.env].slice();
    G.brief={list:nl.map(function(t){var tp=tipFor(t);return {type:t,tip:tp.text};}),old:availHaz(cfg.env).filter(function(t){return nl.indexOf(t)<0;}),age:0};
    scene='BRIEF';
  }
  if(cfg.boss&&!endl)bossInit();
  bus.emit('levelStart',G);
}
/* tiered court obstacles: L1-3 clear, L4-7 puddles + street clutter, L8+ pedestrians + gusty wind */
function genField(cfg){
  var rng=mulberry((cfg.seed||cfg.n)*7919+13),obst=[],pud=[],i,j,tries;
  var xMin=70,xMax=AW-70,yMin=340,yMax=570,lanes=[];
  for(i=0;i<cfg.movers;i++)lanes.push(yMin-30+(yMax-yMin+60)*((i+1)/(cfg.movers+1)));
  var KINDS=[[['bike',104,42],['trash',46,50]],[['desk',84,46],['bench',132,32]],[['barrel',46,46],['crate',66,52],['cart',100,44]],
    [['umbrella',96,44],['castle',50,46],['banca',124,38]],[['log',124,36],['stump',46,44],['bush',70,46]],[['rock',58,50],['rock',88,54],['stump',44,40]]];
  var AE=ENVART[cfg.env],kinds=KINDS[AE];
  for(i=0;i<cfg.blockers;i++){
    for(tries=0;tries<160;tries++){
      var k=kinds[Math.floor(rng()*kinds.length)];
      var o={type:k[0],dyn:false,block:true,w:k[1],h:k[2],vx:0,x:xMin+rng()*(xMax-xMin),y:yMin+rng()*(yMax-yMin)};
      var ok=true;
      for(j=0;j<obst.length&&ok;j++){var p=obst[j];if(Math.abs(o.x-p.x)<(o.w+p.w)/2+60&&Math.abs(o.y-p.y)<(o.h+p.h)/2+50)ok=false;}
      if(ok){obst.push(o);break;}
    }
  }
  for(i=0;i<cfg.puddles;i++){
    for(tries=0;tries<120;tries++){
      var q={x:xMin-20+rng()*(xMax-xMin+40),y:yMin-50+rng()*(yMax-yMin+100),rx:56+rng()*28,ry:28+rng()*12,kind:['water','water','water','seaweed','vine','lava'][AE],ph:rng()*6};
      var ok2=true;
      for(j=0;j<obst.length&&ok2;j++){var b=obst[j];if(Math.abs(q.x-b.x)<q.rx+b.w/2+10&&Math.abs(q.y-b.y)<q.ry+b.h/2+10)ok2=false;}
      for(j=0;j<pud.length&&ok2;j++)if(Math.abs(q.x-pud[j].x)<q.rx+pud[j].rx&&Math.abs(q.y-pud[j].y)<q.ry+pud[j].ry+10)ok2=false;
      if(ok2){pud.push(q);break;}
    }
  }
  for(i=0;i<cfg.movers;i++){
    var mt='ped',mw=40,mh=28,sm=1;
    if(AE===2&&i%2===1){mt='trike';mw=100;mh=44;}
    else if(AE===3){mt='crab';mw=36;mh=22;sm=1.4;}
    else if(AE===4){mt='carabao';mw=96;mh=44;sm=0.6;}
    else if(AE===5){mt='crab';mw=36;mh=22;sm=1.1;}
    var mv={type:mt,dyn:true,block:true,w:mw,h:mh,x:rng()*AW,y:lanes[i],
      vx:(rng()<.5?-1:1)*(55+rng()*50+((cfg.n-1)%30+1)*1.3)*sm,sh:Math.floor(rng()*5)};
    for(tries=0;tries<60;tries++){
      var clash=false;
      for(j=0;j<obst.length&&!clash;j++){var s2=obst[j];if(!s2.dyn&&Math.abs(mv.x-s2.x)<(mv.w+s2.w)/2+10&&Math.abs(mv.y-s2.y)<(mv.h+s2.h)/2+10)clash=true;}
      if(!clash)break;
      mv.x=rng()*AW;
    }
    obst.push(mv);
  }
  return {obst:obst,pud:pud};
}
function pushOut(e,r){
  for(var i=0;i<G.obst.length;i++){
    var o=G.obst[i],hx=o.w/2,hy=o.h/2;
    var cx=clamp(e.x,o.x-hx,o.x+hx),cy=clamp(e.y,o.y-hy,o.y+hy);
    var dx=e.x-cx,dy=e.y-cy,d=Math.hypot(dx,dy);
    if(d<r){
      if(d>0.001){e.x+=dx/d*(r-d);e.y+=dy/d*(r-d);}
      else{
        var px=hx-Math.abs(e.x-o.x),py=hy-Math.abs(e.y-o.y);
        if(px<py)e.x+=(e.x<o.x?-1:1)*(px+r);else e.y+=(e.y<o.y?-1:1)*(py+r);
      }
    }
  }
}
/* biome ground hazards: puddles (street/school/city), slippery seaweed (beach), tangled roots and vines (forest),
   flickering shadows (haunted house - only bite while they are "on") */
var PATCH_MUL={water:0.5,seaweed:1.15,vine:0.35,shadow:0.5,lava:0.45};
function patchAt(e){
  for(var i=0;i<G.pud.length;i++){
    var p=G.pud[i],dx=(e.x-p.x)/p.rx,dy=(e.y-p.y)/p.ry;
    if(dx*dx+dy*dy<1&&(p.kind!=='shadow'||shadowOn(p)))return p;
  }
  return null;
}
function shadowOn(p){return Math.sin(G.time*2.2+p.ph)>0.1;}
function patchMul(e){var p=patchAt(e);return p?PATCH_MUL[p.kind]:1;}
function inSlip(e){var p=patchAt(e);return !!(p&&p.kind==='seaweed');}
function inObstacle(x,y,pad){
  for(var i=0;i<G.obst.length;i++){var o=G.obst[i];if(Math.abs(x-o.x)<o.w/2+pad&&Math.abs(y-o.y)<o.h/2+pad)return true;}
  return false;
}

/* ---------- popups, particles, toasts ---------- */
function pop(x,y,text,col){G.pops.push({x:x,y:y,text:text,col:col||'#fff',t:1.2});}
function toast(text,col){G.toasts.push({text:text,col:col||'#fff',t:2.6});if(G.toasts.length>3)G.toasts.shift();}
function burst(x,y,col,n,spd){
  for(var i=0;i<n;i++){var a=Math.random()*6.283,s=rnd(.3,1)*(spd||70)*K*.6;
    G.parts.push({x:x,y:y,vx:Math.cos(a)*s,vy:Math.sin(a)*s-30,t:0,life:rnd(.35,.8),col:col,size:rnd(3,7),g:400});}
}

/* ---------- manual aim + power meter ----------
   A/D (P1) or Left/Right (P2) rotate the reticle while in the safe zone with a slipper.
   SPACE #1 starts the oscillating meter, SPACE #2 throws.                           */
function inAim(h){return h.meter.on;}   // sighting is active only after the 1st press
function canThrow(h){
  if(G.clearing)return 'LEVEL ALMOST CLEAR!';
  if(G.can.state!=='up')return 'CAN IS DOWN - GRAB SLIPPERS!';
  if(h.y<G.line)return 'THROW FROM THE SAFE ZONE!';
  if(h.ammo<=0)return 'NO SLIPPERS - PICK THEM UP';
  if(h.stun>0)return 'STUNNED!';
  return null;
}
function pressThrow(who){
  if(scene!=='PLAY')return;
  var h=null,i;
  if(who==='auto'){
    for(i=0;i<G.hitters.length;i++)if(G.hitters[i].meter.on)h=G.hitters[i];
    if(!h){
      var order=G.hitters.slice().sort(function(a,b){return b.last-a.last;});
      for(i=0;i<order.length;i++)if(!canThrow(order[i])){h=order[i];break;}
      if(!h){toast(canThrow(order[0]),'#ff9a8a');return;}
    }
  }else h=G.hitters[who]||G.hitters[0];
  if(h.meter.on){lockThrow(h);return;}
  var why=canThrow(h);
  if(why){toast(h.name+': '+why,'#ff9a8a');return;}
  var sy0=h.y-30;h.aim=clamp(Math.atan2(G.can.x-h.x,-(G.can.y-sy0)),-AIM_MAX,AIM_MAX);   // sighting starts pointing at the can
  h.meter.on=true;h.meter.p=-Math.PI/2;h.meter.m=-1;
  SFX.lock();
}
function meterSpeed(h){
  var s=G.cfg.meterSpeed*(1-0.12*SAVE.up.slow)*chMeter(h)*(h.jit>0?1.7:1);
  if(G.haz&&G.haz.type==='STORM')s*=1.2;
  if(G.haz&&G.haz.type==='EARTHQUAKE')s*=1.15;
  return s;
}
function makeSlipper(h,ang,range,echo){
  var sy=h.y-30,tx=h.x+Math.sin(ang)*range,ty=sy-Math.cos(ang)*range;
  return {owner:h.id,x:h.x,y:sy,x0:h.x,y0:sy,px:h.x,py:sy,tx:tx,ty:ty,dx:0,dy:0,u:0,T:0.75+range/1900,z:0,rot:0,
    state:'fly',ghost:h.ghost&&!echo,perfect:false,echo:!!echo,gt:0,trailT:0,heavy:Math.min(0.9,0.25*SAVE.up.heavy+chWind(h))};
}
function lockThrow(h){
  var paloLv=h.palo;h.palo=0;
  var m=h.meter.m;
  h.meter.on=false;
  var can=G.can,sy=h.y-30,d=Math.hypot(can.x-h.x,can.y-sy);
  var range=d*(1+0.4*m);                       // centre of the meter = exactly the can's distance
  var s=makeSlipper(h,h.aim,range,false);
  s.T=s.T/(1+0.12*sl(h,'JUAN',0));
  if(h.rico){h.rico=0;s.rico=2+sl(h,'JUAN',3);s.heavy=0.9;s.ricoShot=true;s.col='#ff8a3a';}
  s.perfect=Math.abs(m)<chPerfect(h)||paloLv>0;
  if(paloLv>=2)s.heavy=0.9;
  if(paloLv>=3)s.dpts=true;
  if(paloLv>0)s.palo=true;
  G.slippers.push(s);
  if(h.multi>0){
    G.slippers.push(makeSlipper(h,h.aim-0.15,range*0.98,true));
    G.slippers.push(makeSlipper(h,h.aim+0.15,range*1.02,true));
  }
  h.ghost=false;h.ammo--;h.throwAnim=0.25;
  SFX.toss();
}
function ownerOf(s){return G.hitters[s.owner]||G.hitters[0];}
function hitCan(s){
  var can=G.can,h=ownerOf(s);
  G.taya.sadT=1.6;G.taya.laugh=0;
  knockCan(s,h);
  G.hits++;G.streak++;G.bestStreak=Math.max(G.bestStreak,G.streak);
  var pts=Math.round((100+(s.perfect?50:0)+(G.streak>1?25*(G.streak-1):0))*G.mult*chScore(h)*(s.dpts?2:1));
  G.score+=pts;h.score+=pts;
  pop(can.x,can.y-100,(s.perfect?'PERFECT! +':'+')+pts,s.perfect?'#7dff7a':'#ffe66a');
  G.mult=Math.min(4,G.mult+0.25);
  shake=12;
  burst(can.x,can.y-20,'#eef3f8',18,90);
  if(s.echo){s.dead=true;}
  else if(s.ghost){s.state='ghost';s.gt=0.9;s.x=can.x;s.y=can.y;s.z=0;}
  else{var a=rnd(0,6.283);s.state='ground';s.x=clamp(can.x+Math.cos(a)*rnd(90,140),24,AW-24);s.y=clamp(can.y+Math.abs(Math.sin(a))*rnd(60,110),TOP+10,BOT);s.z=0;pushOut(s,14);}
  SFX.hit();
  if(!G.boss)popBang(can.x,can.y-70,pick(['PAK!','BLAG!','KLANG!']),s.perfect?'#7dff7a':'#ffd23f');
  bus.emit('canHit',s);
  spawnPog(can.x+rnd(-200,200),can.y+rnd(110,220));
  if(G.hits>=G.need){G.clearing=true;G.clearT=1.3;}
}
/* the lata is knocked away across the ground: it flies, tumbles, bounces off the court edges and slides to rest */
function knockCan(s,h){
  if(G.boss){bossHit(s,h);return;}
  var can=G.can,dx=can.x-h.x,dy=can.y-h.y,d=Math.hypot(dx,dy)||1,ang=Math.atan2(dy,dx)+rnd(-0.75,0.75);
  var sp=rnd(430,560)*(s.heavy?1.15:1)*(s.perfect?1.1:1)*(1+0.2*sl(h,'JUAN',1))*(1+0.15*sl(h,'SANTIAGO',3));
  can.sq=0.5;can.state='down';can.phase='fly';can.carried=false;can.setP=0;
  can.vx=Math.cos(ang)*sp;can.vy=Math.sin(ang)*sp*0.8;
  can.z=18;can.vz=300;can.rot=0;can.rv=(Math.random()<.5?-1:1)*rnd(9,14);can.bn=0;can.fall=can.vx>=0?1:-1;
  SFX.thud&&SFX.thud();
}
function updateCan(dt){
  var can=G.can;
  if(can.sq>0)can.sq-=dt;
  if(can.state==='up'||can.carried)return;
  if(can.phase!=='fly')return;
  can.x+=can.vx*dt;can.y+=can.vy*dt;
  can.z+=can.vz*dt;can.vz-=1100*dt;
  var drag=Math.exp(-(can.z<=0?2.3:0.7)*dt);can.vx*=drag;can.vy*=drag;
  if(can.z<=0){can.z=0;if(can.vz<-90){can.vz=-can.vz*0.42;can.bn++;shake=Math.max(shake,4);SFX.click&&SFX.click();burst(can.x,can.y,'#d8d2c4',5,40);}else can.vz=0;}
  can.rot+=can.rv*dt;can.rv*=Math.exp(-(can.z<=0?1.8:0.3)*dt);
  var xl=34,xr=AW-34,yt=TOP+30,yb=G.line-40;
  if(can.x<xl){can.x=xl;can.vx=Math.abs(can.vx)*0.6;}
  if(can.x>xr){can.x=xr;can.vx=-Math.abs(can.vx)*0.6;}
  if(can.y<yt){can.y=yt;can.vy=Math.abs(can.vy)*0.6;}
  if(can.y>yb){can.y=yb;can.vy=-Math.abs(can.vy)*0.6;}
  if(can.z<=0&&Math.hypot(can.vx,can.vy)<28){
    can.phase='rest';can.vx=can.vy=0;can.z=0;can.rot=(can.fall>=0?1:-1)*1.45;
    pushOut(can,26);pushOut(can,26);can.x=clamp(can.x,xl,xr);can.y=clamp(can.y,yt,yb);
  }
}
function dropCan(){var c=G.can;if(c.carried){c.carried=false;c.phase='rest';c.setP=0;c.rot=(c.fall>=0?1:-1)*1.45;c.z=0;}}
function landSlipper(s,blocked){
  s.z=0;
  s.x=clamp(s.x,24,AW-24);s.y=clamp(s.y,TOP+10,BOT);
  burst(s.x,s.y,'#d8d2c4',6,40);
  if(s.echo){s.dead=true;return;}
  if(skin().apoy&&typeof burnAt==='function')burnAt(s.x,s.y);
  pushOut(s,14);
  G.mult=Math.max(1,G.mult-0.25);G.streak=0;
  pop(s.x,s.y-40,blocked?'BLOCKED!':'MISS','#ff9a8a');
  if(s.ghost){s.state='ghost';s.gt=0.9;}
  else s.state='ground';
}

/* ---------- mystery pogs: a random buff or a nasty trap ---------- */
function spawnPog(x,y){
  if(G.pogs.length>=3)return;
  var e={x:clamp(x,50,AW-50),y:clamp(y,300,G.line-40),t:16,bob:Math.random()*6};
  if(Math.random()<0.5)e.type=Math.random()<0.7?pick(BUFFS):pick(TRAPS);   // known pog: real identity shown
  pushOut(e,26);
  G.pogs.push(e);
}
function rollPog(){return Math.random()<luckBuff()?pick(BUFFS):pick(TRAPS);}
function applyPog(h,type){
  var d=POGS[type];
  if(type==='GHOST')h.ghost=true;
  else if(type==='SPEED')h.boost=d.dur;
  else if(type==='MULTI')h.multi=d.dur;
  else if(type==='COINS')h.dbl=d.dur;
  else if(type==='MUD')h.mud=d.dur;
  else if(type==='REVERSE')h.rev=d.dur;
  else if(type==='JITTER')h.jit=d.dur;
  else if(type==='RAGE')G.rage=d.dur;
  else if(type==='SPARE'){h.ammo++;h.total=Math.max(h.total,h.ammo);}
  else if(type==='FREEZE'){var T2=G.taya;if(T2&&!G.boss){T2.state='STUN';T2.stun=3;T2.zap=3;if(G.can.carried)dropCan();pop(T2.x,T2.y-110,'FROZEN!','#bfeaff');}}
  toast(h.name+': '+(d.trap?'TRAP! ':'')+d.name,d.trap?'#ff9a8a':'#fff3a0');
  pop(h.x,h.y-90,d.trap?'TRAP! '+d.name:d.name,d.trap?'#ff6a6a':'#fff3a0');
  burst(h.x,h.y-30,d.bg,16,80);
  if(d.trap)SFX.trap();else SFX.pog();
  bus.emit('pog',{type:type,trap:!!d.trap});
}
function updatePogs(dt){
  G.pogTimer-=dt;
  if(G.pogTimer<=0){G.pogTimer=rnd(10,15);spawnPog(rnd(90,AW-90),rnd(320,G.line-50));}
  for(var i=0;i<G.pogs.length;i++){
    var k=G.pogs[i],j;k.t-=dt;
    for(j=0;j<G.hitters.length&&!k.dead;j++){
      if(dist(k,G.hitters[j])<40*chPick(G.hitters[j])){k.dead=true;applyPog(G.hitters[j],k.type||rollPog());}
    }
    // a thrown slipper that strikes a pog triggers it for the thrower
    for(j=0;j<G.slippers.length&&!k.dead;j++){
      var s=G.slippers[j];
      if(s.state==='fly'&&s.z<80&&Math.hypot(s.x-k.x,s.y-(k.y))<36){k.dead=true;applyPog(ownerOf(s),k.type||rollPog());}
    }
    if(k.t<=0)k.dead=true;
  }
  G.pogs=G.pogs.filter(function(k){return !k.dead;});
}

/* ---------- field coins (+10 each, x2 with the Double Coins pog) ---------- */
function spawnCoin(){
  for(var t=0;t<30;t++){
    var x=rnd(60,AW-60),y=rnd(310,G.line-30);
    if(!inObstacle(x,y,40)){G.coins.push({x:x,y:y,t:18,ph:Math.random()*6});return;}
  }
}
function updateCoins(dt){
  G.coinTimer-=dt;
  if(G.coinTimer<=0){G.coinTimer=rnd(3.5,6);if(G.coins.length<5)spawnCoin();}
  for(var i=0;i<G.coins.length;i++){
    var c=G.coins[i];c.t-=dt;
    for(var j=0;j<G.hitters.length;j++){
      var h=G.hitters[j];
      if(dist(c,h)<38*chPick(h)){
        var v=Math.round((h.dbl>0?20:10)*chCoin(h));
        c.dead=true;SAVE.coins+=v;G.coinsLvl+=v;
        pop(c.x,c.y-50,'+'+v+' COINS','#ffd23f');SFX.coin();burst(c.x,c.y-20,'#ffd23f',8,60);break;
      }
    }
    if(c.t<=0)c.dead=true;
  }
  G.coins=G.coins.filter(function(c){return !c.dead;});
}

/* =========================================================================
   ENDLESS 2-PLAYER CO-OP SURVIVAL: wave after wave in the chosen biome, 3-choice coin upgrades between waves
   ========================================================================= */
var UPG={
  spd:{n:'BILIS',d:'Both players run 8% faster',base:50},
  life:{n:'DAGDAG BUHAY',d:'+1 shared life right now',base:90},
  ammo:{n:'EXTRA PAMATO',d:'+1 pamato for each player',base:90},
  wind:{n:'BIGAT NA PAMATO',d:'Wind pushes slippers 20% less',base:60},
  meter:{n:'TIYAGA',d:'Power meter swings 8% slower',base:60},
  pick:{n:'KAMAY NA BAKAL',d:'Pickup range +20%',base:50},
  inv:{n:'PANANGGOL',d:'+0.8s safe time after a tag',base:70},
  coin:{n:'SINAG NG PERA',d:'Coins are worth 40% more',base:60},
  slow:{n:'PAGOD NA TAYA',d:'The Taya runs 6% slower',base:80},
  perf:{n:'MATALAS NA MATA',d:'Wider PERFECT zone on the meter',base:70},
  stun:{n:'TULOG-TULOG',d:'Taya is stunned 0.7s longer after a tag',base:70}
};
var UPG_ORDER=['spd','life','ammo','wind','meter','pick','inv','coin','slow','perf','stun'];
function upgCost(id){return Math.round((UPG[id].base+12*(RUN.wave-1))*(1+0.25*(RUN.up[id]||0)));}
function waveCfg(env,w){
  var base=LEVELS[env*10+Math.min(9,Math.floor((w-1)/2))],c={},k;
  for(k in base)c[k]=base[k];
  c.tayaSpeed=base.tayaSpeed*(1+Math.min(0.5,0.02*Math.max(0,w-20)))*(1-Math.min(0.3,0.06*(RUN.up.slow||0)));
  c.seed=env*1000+w;c.wave=w;
  return c;
}
function startRun(b){
  if(!survOpen(b)){
    var g=SURV_GATE[b];selMsg='LOCKED! Reach '+g[0]+' points OR '+g[1]+' stars (you have '+survPts()+' pts / '+survStars()+' stars).';selMsgT=3;SFX.nope();return;
  }
  setPlayers(2);
  RUN={env:b,wave:1,up:{},lives:4+lifeBonus(),score:0,stars:0,choices:[],picked:-1,over:false};
  startLevel(LEVELS[b*10].n,waveCfg(b,1));SFX.start();
}
function nextWave(){
  if(!RUN)return;
  RUN.wave++;RUN.lives=Math.min(9,G.lives+(RUN.wave%5===0?1:0));
  var c=waveCfg(RUN.env,RUN.wave);
  startLevel(c.n,c);SFX.start();
}
function rollChoices(){
  var pool=UPG_ORDER.slice(),out=[];
  while(out.length<3){var i=Math.floor(Math.random()*pool.length);out.push(pool.splice(i,1)[0]);}
  RUN.choices=out.map(function(id){return {id:id,cost:upgCost(id)};});
  RUN.picked=-1;
}
function pickUpgrade(k){
  var ch=RUN.choices[k];
  if(!ch)return;
  if(RUN.picked>=0){SFX.nope();return;}
  if(SAVE.coins<ch.cost){shopSay('Not enough coins!');selMsg='Not enough coins - need '+(ch.cost-SAVE.coins)+' more.';selMsgT=2;SFX.nope();return;}
  SAVE.coins-=ch.cost;RUN.up[ch.id]=(RUN.up[ch.id]||0)+1;RUN.picked=k;
  if(ch.id==='life'){G.lives++;}
  SFX.buy();saveGame();
}
function finishWave(){
  var c=G.cfg;
  var st=[G.score>=G.goal,G.lt<=c.fastTime,(G.livesLost===0&&G.pamatoLost===0)||G.perfectRetr>=2];
  var stars=(st[0]?1:0)+(st[1]?1:0)+(st[2]?1:0);
  var award=[10,30,60,100][stars]+Math.min(60,RUN.wave*4);
  RUN.score+=G.score;RUN.stars+=stars;
  SAVE.pts+=G.score;SAVE.es+=stars;SAVE.coins+=award;
  SAVE.eb[RUN.env]=Math.max(SAVE.eb[RUN.env],RUN.wave);SAVE.ebs[RUN.env]=Math.max(SAVE.ebs[RUN.env],RUN.score);
  G.result={st:st,stars:stars,award:award};
  rollChoices();
  saveGame();postBoard(PROF);SFX.clear();
  scene='UPGRADE';sel=0;celebrateInit();
}
function endRun(reason){
  var part=G.score;RUN.score+=part;SAVE.pts+=part;
  SAVE.ebs[RUN.env]=Math.max(SAVE.ebs[RUN.env],RUN.score);
  RUN.over=true;RUN.reason=reason;
  saveGame();postBoard(PROF);
}

""";

    private static final String JS_SIM = """

/* =========================================================================
   engine part 3: AI Taya, hitters, slippers, frame update, results, key routing
   ========================================================================= */
function moveEnt(e,tx,ty,spd,dt,r){
  var dx=tx-e.x,dy=ty-e.y,d=Math.hypot(dx,dy);
  if(d<1)return;
  var ux=dx/d,uy=dy/d;
  if(e.side>0){
    var c=Math.cos(e.sideDir*1.3),s=Math.sin(e.sideDir*1.3);
    var rx=ux*c-uy*s,ry=ux*s+uy*c;ux=rx;uy=ry;e.side-=dt;
  }
  var step=Math.min(d,spd*dt),ox=e.x,oy=e.y;
  e.x+=ux*step;e.y+=uy*step;
  hazPush(e,dt,0.3);
  pushOut(e,r);
  e.x=clamp(e.x,24,AW-24);e.y=clamp(e.y,TOP+10,e.maxY||BOT);
  if(ux>0.1)e.face=1;else if(ux<-0.1)e.face=-1;
  var moved=Math.hypot(e.x-ox,e.y-oy);
  if(step>0.5&&moved<step*0.35){
    e.stuck+=dt;
    if(e.stuck>0.25){e.side=0.6;e.sideDir=Math.random()<.5?-1:1;e.stuck=0;}
  }else e.stuck=0;
}
function predictTarget(T,h,spd){
  var d=dist(T,h),t=Math.min(1.1,d/Math.max(spd,1))*G.cfg.predict;
  return {x:clamp(h.x+h.vx*t,24,AW-24),y:Math.min(T.maxY,clamp(h.y+h.vy*t,TOP+10,BOT))};
}
/* the lata can never trap Taya: free approach point + a no-clip fallback if she is wedged against scenery */
function inObst(x,y,r){
  for(var i=0;i<G.obst.length;i++){var o=G.obst[i];
    if(Math.abs(x-o.x)<o.w/2+r&&Math.abs(y-o.y)<o.h/2+r)return true;}
  return false;
}
function canApproach(can){
  var offs=[[0,34],[-36,20],[36,20],[0,-34],[-42,-6],[42,-6],[0,56]],i,x,y;
  for(i=0;i<offs.length;i++){
    x=clamp(can.x+offs[i][0],30,AW-30);y=Math.min(can.y+offs[i][1],G.line-26);
    if(!inObst(x,y,16))return {x:x,y:y};
  }
  return {x:can.x,y:can.y};
}
function slideTo(e,tx,ty,spd,dt){          // straight, ignores obstacles (stuck rescue only)
  var dx=tx-e.x,dy=ty-e.y,d=Math.hypot(dx,dy);
  if(d<1)return;
  var st=Math.min(d,spd*dt);e.x+=dx/d*st;e.y+=dy/d*st;
  e.x=clamp(e.x,24,AW-24);e.y=clamp(e.y,TOP+10,G.line-14);
  if(dx>3)e.face=1;else if(dx<-3)e.face=-1;
}
function updateTaya(dt){
  var T=G.taya,C=G.cfg,can=G.can,i;
  if(T.laugh>0)T.laugh-=dt;if(T.zap>0)T.zap-=dt;if(T.sadT>0)T.sadT-=dt;
  var out=0;for(i=0;i<G.hitters.length;i++)if(G.hitters[i].y<G.line)out++;
  var pincer=(G.two&&out>=2)?1.1:1;                 // 2P: both hitters in the field = she sprints harder
  var spd=C.tayaSpeed*K*G.spdMul*pincer*hazMul(T)*(1-(1-patchMul(T))*0.8)*(G.rage>0?1.35:1);
  if(G.boss)spd*=0.85;
  for(i=0;i<G.hitters.length;i++){var nh=G.hitters[i],na=sl(nh,'MARIA',3);if(na&&dist(T,nh)<170)spd*=1-0.08*na;}
  var react=C.reaction*G.reactMul;
  if(T.state==='STUN'){if(can.carried)dropCan();T.stun-=dt;if(T.stun<=0)T.state='GUARD';return;}
  if(can.state==='down'){
    G.chase=false;
    if(T.state==='PICK'){
      T.t-=dt;T.face=1;T.fetchT=0;
      if(T.t<=0){T.state='CARRY';can.carried=true;can.phase='held';can.setP=0;T.fetchT=0;SFX.pog&&SFX.pog();}
    }else if(T.state==='CARRY'){
      var mx=CAN_X,my=CAN_Y+54;
      T.fetchT=(T.fetchT||0)+dt;
      if(T.fetchT>2.5)slideTo(T,mx,my,spd*0.85,dt);else moveEnt(T,mx,my,spd*0.85,dt,14);
      can.x=T.x;can.y=T.y;
      if(Math.hypot(T.x-mx,T.y-my)<14){            // placed on its marker: no repair delay, the chase resumes at once
        can.state='up';can.carried=false;can.phase='rest';can.setP=0;can.rot=0;can.z=0;can.x=CAN_X;can.y=CAN_Y;can.sq=0.5;
        T.state='GUARD';T.react=0;
        burst(can.x,can.y,'#eef3f8',12,60);
        SFX.canUp();bus.emit('canUp');
      }
    }else{
      if(T.state!=='TO_CAN')T.fetchT=0;
      T.state='TO_CAN';                                  // run over to wherever the lata landed
      var ap=canApproach(can),tx=ap.x,ty=ap.y;
      T.fetchT=(T.fetchT||0)+dt;
      if(T.fetchT>2.5)slideTo(T,tx,ty,spd*1.15,dt);else moveEnt(T,tx,ty,spd*1.15,dt,14);
      if(can.phase==='rest'&&(Math.hypot(T.x-tx,T.y-ty)<26||Math.hypot(T.x-can.x,T.y-can.y)<42)){T.state='PICK';T.t=0.45;}
    }
    return;
  }
  if(T.state==='TO_CAN'||T.state==='SETTING'||T.state==='PICK'||T.state==='CARRY')T.state='GUARD';
  var dc=G.decoy;
  if(dc&&dc.t>0&&T.state!=='STUN'&&!G.boss){
    var dd=Math.hypot(T.x-dc.x,T.y-dc.y);
    if(dd<dc.r*2.6+60||T.state==='DECOY'){
      T.state='DECOY';G.chase=false;moveEnt(T,dc.x,Math.min(dc.y,T.maxY),spd,dt,14);
      if(Math.hypot(T.x-dc.x,T.y-Math.min(dc.y,T.maxY))<28){T.state='STUN';T.stun=dc.stun;T.zap=dc.stun;dc.t=0;pop(T.x,T.y-110,'CONFUSED!','#cfd6e6');popBang(T.x,T.y-70,'HUH?!','#cfd6e6');dust(T.x,T.y,10,'#d8dce8');}
      return;
    }
  }
  if(T.state==='DECOY')T.state='GUARD';
  var target=null,bd=1e9;
  for(i=0;i<G.hitters.length;i++){
    var h=G.hitters[i];
    if(h.y<G.line&&h.inv<=0){var d=dist(T,h);if(d<bd){bd=d;target=h;}}
  }
  if(target){
    if(T.state!=='CHASE'){T.state='CHASE';T.react=react;}
    if(T.react>0){T.react-=dt;T.face=target.x>=T.x?1:-1;}
    else{
      var pt=predictTarget(T,target,spd);
      T.aimX=pt.x;T.aimY=pt.y;
      moveEnt(T,pt.x,pt.y,spd,dt,14);
      if(dist(T,target)<TAG_R)tagHitter(target);
    }
    G.chase=true;
  }else{
    T.state='GUARD';G.chase=false;
    var gx=CAN_X+Math.sin(G.time*1.1)*150,gy=CAN_Y+72+Math.sin(G.time*1.7)*22;
    if(G.boss){gx=AW/2+Math.sin(G.time*0.45)*(AW/2-150);gy=CAN_Y+130+(Math.sin(G.time*0.7)+1)*60;}
    moveEnt(T,gx,gy,spd*0.7,dt,14);
  }
}
function tagHitter(h){
  G.lives--;G.livesLost++;G.mult=1;G.streak=0;G.stats.tags++;
  h.inv=chInv(h);h.meter.on=false;
  burst(h.x,h.y-30,'#ff5a4a',20,100);
  pop(h.x,h.y-90,'TAGGED!','#ff5a4a');
  h.x=G.two?AW/2-110+h.id*220:AW/2;h.y=650;
  G.taya.state='STUN';G.taya.stun=1.1+0.7*RU('stun');G.taya.laugh=1.6;
  shake=16;SFX.tag();
  bus.emit('tagged',h);
  if(G.lives<=0)failLevel('TAYA TAGGED ALL YOUR LIVES');
}

/* ---------- hitters: movement, aim rotation, meter ---------- */
function inputFor(h){
  var L,R,U,D;
  if(h.id===0){L=keys.KeyA;R=keys.KeyD;U=keys.KeyW;D=keys.KeyS;}                 // Player 1: WASD only
  else{L=keys.ArrowLeft;R=keys.ArrowRight;U=keys.ArrowUp;D=keys.ArrowDown;}     // Player 2: arrow keys only
  var sg=h.rev>0?-1:1;
  return {x:sg*((R?1:0)-(L?1:0)),y:sg*((D?1:0)-(U?1:0))};
}
function updateHitters(dt){
  for(var i=0;i<G.hitters.length;i++){
    var h=G.hitters[i],ox=h.x,oy=h.y,inp=inputFor(h),ix=inp.x,iy=inp.y;
    if(h.stun>0){h.stun-=dt;ix=0;iy=0;h.meter.on=false;}
    else if(h.meter.on){                                 // sighting: A/D (P1) or Left/Right (P2) fine-tune the angle
      if(ix){h.aim=clamp(h.aim+ix*AIM_ROT*dt,-AIM_MAX,AIM_MAX);h.last=performance.now();}
      ix=0;iy=0;
    }
    if(h.jit>0&&h.meter.on)h.aim=clamp(h.aim+(Math.random()-.5)*dt*5,-AIM_MAX,AIM_MAX);
    h.moving=!!(ix||iy);
    if(h.moving){var n=Math.hypot(ix,iy);ix/=n;iy/=n;h.last=performance.now();if(ix)h.face=ix>0?1:-1;}
    if(h.acd>0)h.acd-=dt;
    if(h.dash>0){h.dash-=dt;if(Math.random()<dt*30)dust(h.x,h.y,1,'#cfeeff');}
    var sp=BASE_SPEED*(1+0.08*SAVE.up.sprint)*chSpeed(h)*(h.boost>0?1.4:1)*(h.dash>0?1.9:1)*(h.mud>0?(sl(h,'BERTO',3)?0.7:0.4):1)*hazMul(h)*patchMul(h);
    if(inSlip(h)){                                       // slippery seaweed: you keep sliding
      h.svx+=(ix*sp-h.svx)*Math.min(1,dt*2);h.svy+=(iy*sp-h.svy)*Math.min(1,dt*2);
    }else{h.svx=ix*sp;h.svy=iy*sp;}
    h.x+=h.svx*dt;h.y+=h.svy*dt;
    hazPush(h,dt,0.45);
    pushOut(h,HITTER_R);
    h.x=clamp(h.x,24,AW-24);h.y=clamp(h.y,TOP+14,BOT);
    h.vx=(h.x-ox)/Math.max(dt,0.001);h.vy=(h.y-oy)/Math.max(dt,0.001);
    if(h.boost>0)h.boost-=dt;
    if(h.multi>0)h.multi-=dt;
    if(h.dbl>0)h.dbl-=dt;
    if(h.mud>0)h.mud-=dt;
    if(h.rev>0)h.rev-=dt;
    if(h.jit>0)h.jit-=dt;
    if(h.inv>0)h.inv-=dt;
    if(h.throwAnim>0)h.throwAnim-=dt;
    if(h.meter.on){
      if(h.y<G.line||G.can.state!=='up'){h.meter.on=false;}
      else{h.meter.p+=meterSpeed(h)*dt;h.meter.m=(2/Math.PI)*Math.asin(Math.sin(h.meter.p));}
    }
  }
}
function trailFx(s){
  var tr=skin().trail;
  if(!tr)return;
  var x=s.x,y=s.y-s.z;
  if(tr==='purple')G.parts.push({x:x+rnd(-6,6),y:y+rnd(-6,6),vx:rnd(-30,30),vy:rnd(-30,30),t:0,life:.5,col:pick(['#b98cff','#8b4dff','#e4d2ff']),size:rnd(4,8),g:0});
  else if(tr==='petal')G.parts.push({x:x+rnd(-8,8),y:y+rnd(-4,4),vx:rnd(-40,40),vy:rnd(10,50),t:0,life:.8,col:pick(['#ff9ec7','#ffffff','#ffd0e4']),size:rnd(5,9),g:20});
  else if(tr==='glyph')G.parts.push({x:x+rnd(-8,8),y:y+rnd(-8,8),vx:rnd(-24,24),vy:rnd(-24,24),t:0,life:.8,col:pick(['#6a3a14','#a8742f','#fbf0d0']),size:rnd(3,7),g:0});
  else if(tr==='jeep')G.parts.push({x:x+rnd(-10,10),y:y+rnd(-4,10),vx:rnd(-50,50),vy:rnd(-10,40),t:0,life:.6,col:pick(['#e0262e','#ffd23f','#2a62c9','#ffffff']),size:rnd(4,8),g:0});
  else if(tr==='fire')G.parts.push({x:x+rnd(-8,8),y:y+rnd(-4,8),vx:rnd(-24,24),vy:rnd(-70,-20),t:0,life:.55,col:pick(['#ff5a1f','#ffd23f','#ff9a2b']),size:rnd(5,9),g:-30});
  else if(tr==='gold')G.parts.push({x:x+rnd(-8,8),y:y+rnd(-8,8),vx:rnd(-20,20),vy:rnd(-20,20),t:0,life:.65,col:pick(['#ffd23f','#fff3a0','#ffb300']),size:rnd(5,10),g:0});
}
function updateSlippers(dt){
  var i,j,s,T=G.taya;
  for(i=0;i<G.slippers.length;i++){
    s=G.slippers[i];
    if(s.dead)continue;
    if(s.state==='fly'){
      s.px=s.x;s.py=s.y;
      s.u+=dt/s.T;
      var wf=1-s.heavy;
      s.dx+=G.wx*wf*0.55*dt;s.dy+=G.wy*wf*0.55*dt;
      hazFlight(s,dt);
      var u=Math.min(1,s.u);
      s.x=s.x0+(s.tx-s.x0)*u+s.dx;s.y=s.y0+(s.ty-s.y0)*u+s.dy;
      s.z=4*FLIGHT_PEAK*u*(1-u);s.rot+=dt*14;
      s.trailT-=dt;if(s.trailT<=0){s.trailT=0.025;trailFx(s);}
      // the can is struck when the slipper passes low over it
      if(G.can.state==='up'&&u>0.3&&s.z<48&&Math.hypot(s.x-G.can.x,s.y-G.can.y)<(G.boss?60:G.cfg.canR*1.2)){hitCan(s);continue;}
      if(!s.ghost&&s.u>0.1&&Math.hypot(s.x-G.can.x,s.y-G.can.y)>(G.boss?60:G.cfg.canR*1.2)+16){
        var blocked=false;
        for(j=0;j<G.obst.length;j++){
          var o=G.obst[j];
          if(o.block&&Math.abs(s.x-o.x)<o.w/2+6&&Math.abs(s.y-o.y)<o.h/2+6){blocked=true;break;}
        }
        if(blocked){s.x=s.px;s.y=s.py;if(s.rico>0){ricoStart(s,true);continue;}landSlipper(s,true);continue;}
      }
      if(s.u>=1||s.x<-20||s.x>AW+20||s.y<TOP-30){if(s.rico>0){ricoStart(s,false);continue;}landSlipper(s,false);}
    }else if(s.state==='rico'){
      ricoUpdate(s,dt);
    }else if(s.state==='ghost'){
      s.gt-=dt;
      if(s.gt<=0){
        s.dead=true;var o2=ownerOf(s);o2.ammo++;o2.total=Math.max(o2.total,o2.ammo);
        burst(o2.x,o2.y-30,'#cfe0ff',10,50);
      }
    }else if(s.state==='ground'){
      if(T.state==='CHASE'&&T.react<=0&&dist(s,T)<32){
        s.dead=true;G.pamatoLost++;pop(s.x,s.y-50,'TAYA TOOK A PAMATO!','#ff5a4a');SFX.tag();burst(s.x,s.y-10,'#ff9a8a',10,60);
        continue;
      }
      for(j=0;j<G.hitters.length;j++){
        var h=G.hitters[j];
        if(dist(s,h)<PICKUP_R*chPick(h)){
          s.dead=true;h.ammo++;h.total=Math.max(h.total,h.ammo);
          if(h.y<G.line&&G.can.state==='up'&&dist(T,h)<300){
            G.perfectRetr++;G.score+=25;h.score+=25;pop(h.x,h.y-110,'PERFECT RETRIEVAL! +25','#7dff7a');
          }else pop(h.x,h.y-80,'+1','#9ad8ff');
          // 2P synchronized retrieval: both partners grab a pamato within 2.5s of each other
          h.syncT=G.time;
          if(G.two){
            var pr=G.hitters[1-h.id];
            if(G.time-pr.syncT<2.5){G.score+=100;G.syncs++;pop((h.x+pr.x)/2,Math.min(h.y,pr.y)-130,'SYNC RETRIEVAL! +100','#ffd23f');h.syncT=-99;pr.syncT=-99;SFX.sync();}
          }
          SFX.pick();break;
        }
      }
    }
  }
  G.slippers=G.slippers.filter(function(q){return !q.dead;});
}
function pamatoLeft(){
  var n=0,i;
  for(i=0;i<G.hitters.length;i++)n+=G.hitters[i].ammo;
  for(i=0;i<G.slippers.length;i++)if(!G.slippers[i].echo)n++;
  return n;
}
function updatePhase(){
  var p;
  var any=function(a,f){for(var i=0;i<a.length;i++)if(f(a[i]))return true;return false;};
  if(G.clearing)p='LEVEL_CLEAR';
  else if(G.can.state!=='up')p='SCRAMBLE';
  else if(any(G.slippers,function(s){return s.state==='fly';}))p='AIRBORNE';
  else if(any(G.slippers,function(s){return s.state==='ground';})||any(G.hitters,function(h){return h.y<G.line;}))p='SCRAMBLE';
  else p='STANDOFF';
  if(p!==G.phase){G.phase=p;bus.emit('phase',p);}
}
function updateFx(dt){
  var i;
  for(i=0;i<G.parts.length;i++){var p=G.parts[i];p.t+=dt;p.x+=p.vx*dt;p.y+=p.vy*dt;p.vy+=p.g*dt;}
  G.parts=G.parts.filter(function(p){return p.t<p.life;});
  for(i=0;i<G.pops.length;i++){G.pops[i].t-=dt;G.pops[i].y-=40*dt;}
  G.pops=G.pops.filter(function(p){return p.t>0;});
  for(i=0;i<G.toasts.length;i++)G.toasts[i].t-=dt;
  G.toasts=G.toasts.filter(function(t){return t.t>0;});
  if(G.banner&&G.banner.t>0)G.banner.t-=dt;
  if(shake>0)shake=Math.max(0,shake-dt*40);
}
function updatePlay(dt){
  if(G.tut){tutUpdate(dt);return;}
  G.time+=dt;G.lt+=dt;
  if(G.rage>0)G.rage-=dt;
  var i,q;
  for(i=0;i<G.obst.length;i++){
    var o=G.obst[i];
    if(o.ttl!==undefined){o.ttl-=dt;if(o.ttl<=0)o.dead=true;}
    if(o.dyn){
      o.x+=o.vx*dt;
      if(o.vx>0&&o.x>AW+70)o.x=-70;
      if(o.vx<0&&o.x<-70)o.x=AW+70;
      for(q=0;q<G.obst.length;q++){
        var s3=G.obst[q];
        if(!s3.dyn&&Math.abs(o.x-s3.x)<(o.w+s3.w)/2&&Math.abs(o.y-s3.y)<(o.h+s3.h)/2){o.x-=o.vx*dt*1.5;o.vx=-o.vx;break;}
      }
    }
  }
  G.obst=G.obst.filter(function(b){return !b.dead;});
  updateHazard(dt);
  if(scene!=='PLAY')return;
  updateHitters(dt);
  updateSlippers(dt);
  updateCan(dt);
  updateTaya(dt);
  if(G.boss)bossUpdate(dt);
  vfxUpdate(dt);
  updatePogs(dt);
  updateCoins(dt);
  updatePhase();
  updateFx(dt);
  if(G.clearing){
    G.clearT-=dt;
    if(G.clearT<=0&&scene==='PLAY')finishLevel();
    return;
  }
  G.timeLeft-=dt;
  if(G.timeLeft<=0){
    if(G.endless){                                    // endless: overtime costs a shared life instead of ending the run
      G.timeLeft=25;G.lives--;G.livesLost++;G.mult=1;shake=12;SFX.tag();pop(AW/2,300,'OVERTIME! -1 LIFE','#ff5a4a');
      if(G.lives<=0){failLevel('OUT OF LIVES');return;}
    }else{G.timeLeft=0;failLevel("TIME'S UP");return;}
  }
  if(scene==='PLAY'&&pamatoLeft()===0)failLevel('OUT OF PAMATO');
}
function finishLevel(){
  if(G.endless){finishWave();return;}
  var c=G.cfg;
  var st=[G.score>=G.goal,G.lt<=c.fastTime,(G.livesLost===0&&G.pamatoLost===0)||G.perfectRetr>=2];
  var stars=(st[0]?1:0)+(st[1]?1:0)+(st[2]?1:0);
  var award=[0,50,100,200][stars];
  var n=G.level,first=!SAVE.cleared[n-1];
  SAVE.cleared[n-1]=1;
  SAVE.stars[n-1]=Math.max(SAVE.stars[n-1],stars);
  SAVE.hi[n-1]=Math.max(SAVE.hi[n-1],G.score);
  SAVE.coins+=award;
  G.result={st:st,stars:stars,award:award,unlock:(first&&n===10)?'SCHOOL BIOME UNLOCKED!':(first&&n===20)?'CITY BIOME UNLOCKED!':''};
  saveGame();postBoard(PROF);
  SFX.clear();
  bus.emit('levelClear',n);
  if(c.boss){startOutro(c.env);return;}
  scene='LEVEL_CLEAR';sel=0;celebrateInit();
}
function failLevel(reason){
  if(scene!=='PLAY')return;
  for(var i=0;i<G.hitters.length;i++)G.hitters[i].meter.on=false;
  G.failReason=reason;
  if(G.endless&&RUN&&!RUN.over)endRun(reason);
  saveGame();
  scene='LEVEL_FAILED';sel=0;SFX.over();
  bus.emit('levelFailed',reason);
}

/* ---------- key routing per scene ---------- */
function navMenu(code){
  var bs=buttonsFor(scene),enter=(code==='Enter'||code==='NumpadEnter'||code==='Space');
  if(!bs.length)return;
  if(code==='ArrowUp'||code==='KeyW'||(scene==='LEVEL_CLEAR'&&code==='ArrowLeft'))sel=(sel+bs.length-1)%bs.length;
  else if(code==='ArrowDown'||code==='KeyS'||(scene==='LEVEL_CLEAR'&&code==='ArrowRight'))sel=(sel+1)%bs.length;
  else if(enter&&bs[sel]){SFX.click();bs[sel].act();}
}
function okChar(c){return c.length===1&&'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 _-'.indexOf(c)>=0;}
bus.on('key',function(code){
  var enter=(code==='Enter'||code==='NumpadEnter');
  if(scene==='NAME'){
    if(code==='Escape'){scene='PROFILES';sel=0;}
    else if(enter)submitName();
    else if(code==='Backspace')nameBuf=nameBuf.slice(0,-1);
    else if(okChar(lastKey)&&nameBuf.length<12){nameBuf+=lastKey;SFX.lock();}
    return;
  }
  if(code==='KeyM'){muted=!muted;return;}
  switch(scene){
    case 'PROFILES':
      if(code==='ArrowUp'){pSel=Math.max(0,pSel-1);delConfirm=false;}
      else if(code==='ArrowDown'){pSel=Math.min(DB.profiles.length-1,pSel+1);delConfirm=false;}
      else if(enter||code==='Space'){if(delConfirm){deleteProfile(DB.profiles[pSel]);delConfirm=false;profSay('Profile deleted.');}else if(DB.profiles[pSel])chooseProfile(DB.profiles[pSel]);}
      else if(code==='KeyN')startName('NEW');
      else if(code==='KeyR')startName('RENAME');
      else if(code==='Delete'||code==='KeyX'){if(DB.profiles[pSel])delConfirm=true;}
      else if(code==='Escape'){if(delConfirm)delConfirm=false;else scene='MENU';}
      if(pSel<pOff)pOff=pSel;if(pSel>=pOff+5)pOff=pSel-4;
      break;
    case 'STORY':case 'HOWTO':case 'OUTRO':introKey(code,enter);break;
    case 'MODE':
      if(code==='Escape'){scene='MENU';sel=0;}
      else if(code==='Digit1'||code==='Numpad1'){setPlayers(1);openSelect();}
      else if(code==='Digit2'||code==='Numpad2'){setPlayers(2);openSelect();}
      else navMenu(code);
      break;
    case 'MENU':
      if(code==='Space'||enter)goMode();
      else if(code==='KeyS')openShop('MENU');
      else if(code==='KeyR')startHowto('MENU');
      else if(code==='KeyG'){scene='CAT_POG';sel=0;}
      else if(code==='KeyD'){scene='CAT_DIS';sel=0;}
      else if(code==='KeyP'){scene='PROFILES';sel=0;delConfirm=false;}
      else if(code==='KeyC'){if(!needProf()){scene='CHARS';sel=0;}}
      else if(code==='KeyB')openBoard();
      break;
    case 'BOARD':
      if(code==='Escape'||enter||code==='Space'){scene='MENU';sel=0;}
      break;
    case 'RULES':
      if(code==='ArrowLeft')rulesTab=(rulesTab+2)%3;
      else if(code==='ArrowRight')rulesTab=(rulesTab+1)%3;
      else if(code==='Escape'||enter){scene='MENU';sel=0;}
      break;
    case 'CHARS':
      if(code==='ArrowUp')charSel=(charSel+4)%5;
      else if(code==='ArrowDown')charSel=(charSel+1)%5;
      else if(code==='Tab')charSlot=1-charSlot;
      else if(enter||code==='Space')buyChar(CHAR_ORDER[charSel]);
      else if(code==='Escape'){saveGame();scene='MENU';sel=0;}
      break;
    case 'CAT_POG':
      if(code==='ArrowUp')pogSel=(pogSel+POG_ORDER.length-1)%POG_ORDER.length;
      else if(code==='ArrowDown')pogSel=(pogSel+1)%POG_ORDER.length;
      else if(code==='Escape'||enter){scene='MENU';sel=0;}
      break;
    case 'CAT_DIS':
      if(code==='ArrowUp')disSel=(disSel+HAZ_ORDER.length-1)%HAZ_ORDER.length;
      else if(code==='ArrowDown')disSel=(disSel+1)%HAZ_ORDER.length;
      else if(code==='Escape'||enter){scene='MENU';sel=0;}
      break;
    case 'SELECT':
      if(code==='ArrowLeft')selLevel=Math.max(1,selLevel-1);
      else if(code==='ArrowRight')selLevel=Math.min(NLV,selLevel+1);
      else if(code==='ArrowUp')selLevel=Math.max(1,selLevel-5);
      else if(code==='ArrowDown')selLevel=Math.min(NLV,selLevel+5);
      else if(code==='PageUp')selectBiome(biome-1);
      else if(code==='PageDown')selectBiome(biome+1);
      else if(code==='Space'||enter)playLevel(selLevel);
      else if(code==='KeyS')openShop('SELECT');
      else if(code==='KeyU'){ALL_UNLOCKED=!ALL_UNLOCKED;selMsg=ALL_UNLOCKED?'DEV: all levels unlocked':'DEV: normal unlocks';selMsgT=2;}
      else if(code==='Escape'){scene='MENU';sel=0;}
      if(code.indexOf('Arrow')===0)biome=Math.min(5,Math.floor((selLevel-1)/10));
      break;
    case 'SHOP':
      if(code==='Escape'||code==='Backspace')shopBack();
      else if(code==='KeyC'){SAVE.coins+=500;shopSay('DEV: +500 coins');}
      else if(code==='ArrowRight'&&sel<4)sel=4+sel;
      else if(code==='ArrowLeft'&&sel>=4&&sel<8)sel=sel-4;
      else if(code==='ArrowUp'||code==='ArrowDown'||code==='Space'||enter)navMenu(code);
      break;
    case 'PLAY':
      if(G.tut&&tutKey(code,enter))break;
      if(code==='KeyE')useActive(0);
      else if(code==='ShiftRight')useActive(1);
      else if(code==='Space')pressThrow(0);                       // Player 1 uses SPACE (never shared)
      else if(enter||code==='Numpad0'){if(G.players===2)pressThrow(1);}   // Player 2 (co-op only) uses ENTER
      else if(code==='KeyP'||code==='Escape')openPause();
      else if(code==='KeyH'&&G.cfg.env>=0&&false)triggerHazard();
      else if(code==='BracketRight')startLevel(G.level+1);
      else if(code==='BracketLeft')startLevel(G.level-1);
      break;
    case 'PAUSE':
      if(code==='Escape'||code==='KeyP')scene='PLAY';
      else navMenu(code);
      break;
    case 'BRIEF':
      if((code==='Space'||enter)&&G.brief&&G.brief.age>0.5)ackBrief();
      break;
    case 'SURVIVAL':
      if(code==='ArrowLeft'||code==='KeyA')survSel=(survSel+NB-1)%NB;
      else if(code==='ArrowRight'||code==='KeyD')survSel=(survSel+1)%NB;
      else if(code==='Space'||enter)startRun(survSel);
      else if(code==='KeyU'){ALL_UNLOCKED=!ALL_UNLOCKED;selMsg=ALL_UNLOCKED?'DEV: all biomes unlocked':'DEV: normal unlocks';selMsgT=2;}
      else if(code==='Escape'){scene='MENU';sel=0;}
      break;
    case 'UPGRADE':
      if(code==='ArrowLeft'||code==='KeyA')sel=(sel+3)%4;
      else if(code==='ArrowRight'||code==='KeyD')sel=(sel+1)%4;
      else if(code==='Digit1'||code==='Digit2'||code==='Digit3')pickUpgrade(Number(code.slice(5))-1);
      else if(code==='Space'||enter){if(sel<3)pickUpgrade(sel);else nextWave();}
      break;
    case 'LEVEL_CLEAR':
      navMenu(code);
      break;
    case 'LEVEL_FAILED':
      if(code==='Escape')exitToMenu();
      else navMenu(code);
      break;
  }
});
bus.on('canUp',function(){if(G)toast('Taya set the can up - back to the safe zone!','#ffc07a');});
/* ================= v14: story intro, how-to slides, homepage, level-1 tutorial ================= */
var introAfter=null,storyT=0,howI=0,howT=0,howFrom='MENU',tutSeen=false;
function keycap(x,y,label,w,hot){
  w=w||34;RR(x-w/2,y-16+(hot?3:0),w,32,8,hot?'#ffd23f':'#f4ecd6',3);
  txt(label,x,y+(hot?3:0),15,'#1d1b2e','center',false);
}
function fade(a){if(a<=0)return;ctx.fillStyle='rgba(0,0,0,'+Math.min(1,a)+')';ctx.fillRect(0,0,W,H);}
function kid(x,y,i,t,face){
  var cols=[['#38b6ff','#1f3a8a'],['#ff7ab8','#7a2a5a'],['#6adf8c','#245a3a'],['#ffd23f','#7a5a10']][i%4];
  person(x,y,{face:face,skin:i%2?'#d99467':'#f2b88a',hair:'#1d1b2e',shirt:cols[0],stripe:'#fff',pants:cols[1],step:Math.floor(t*8+i)%2===0,scale:3.1,ring:null});
}
function tayaPerson(x,y,face,step,sc){
  var sk=TAYA_SKIN[0];
  person(x,y,{scale:sc||3,face:face,step:step,skin:sk.skin,hair:sk.hair,shirt:sk.shirt,stripe:sk.stripe,pants:sk.pants,band:sk.band,ring:sk.ring,shoe:'#2b2f4a'});
}

/* ---------- cinematic story ---------- */
var STORY=[
  {len:5.0,cap:'Every afternoon in the barangay, the kalye turns into a playground.'},
  {len:5.2,cap:'One lata. A pair of tsinelas. One Taya guarding it. This is Tumbang Preso.'},
  {len:6.2,cap:'Then came Don Alikabok, tech mogul. His bulldozers flatten playgrounds for Smart-Pod VR arcades.'},
  {len:6.2,cap:'His rogue AI robot Taya and weather drones are powered by one beacon: the GOLDEN LATA.'},
  {len:6.4,cap:'Floods, amihan gusts, quakes, brownouts and ashfall now strike every biome.'},
  {len:5.6,cap:'Jun-Jun, Nene, Boyet and Taba travel the country to strike it down with tsinelas.'},
  {len:5.2,cap:'Sigaw ng Sakuna! Tumbahin ang lata, iligtas ang laro!'}];
function startStory(){scene='STORY';storyT=0;sel=0;}
function storyShot(){var t=storyT,i;for(i=0;i<STORY.length;i++){if(t<STORY[i].len)return {i:i,t:t};t-=STORY[i].len;}return {i:STORY.length,t:0};}
function endStory(){startHowto('BOOT');}
function drawStory(){
  var s=storyShot();
  if(s.i>=STORY.length)return;
  var sh=STORY[s.i],t=s.t,len=sh.len,i,env=[0,0,0,3,4,0,0][s.i];
  ctx.save();ctx.beginPath();ctx.rect(0,70,W,580);ctx.clip();
  var z=1+0.07*(t/len);ctx.translate(W/2,H/2);ctx.scale(z,z);ctx.translate(-W/2,-H/2);
  if(s.i===4){
    var types=['FLOOD','AMIHAN','EARTHQUAKE','BROWNOUT','ASHFALL'],k=Math.min(4,Math.floor(t/(len/5)));
    drawDisasterScene(0,70,W,580,types[k]);
    ctx.restore();
    txtT(HAZ[types[k]].label,W/2,150,46,HAZ[types[k]].col,'center');
  }else{
    ctx.drawImage(bgFor(env),0,0,W,H);
    ctx.fillStyle='rgba(255,170,80,.12)';ctx.fillRect(0,0,W,H);
    if(s.i===0){
      drawCan({x:W/2,y:430,state:'up',fall:0});
      for(i=0;i<4;i++){var d=i%2?-1:1,x=((t*60*d+i*330)%1500+1500)%1500-110;kid(x,470+(i%2)*60,i,t,d);}
    }else if(s.i===1){
      drawCan({x:W/2,y:400,state:'up',fall:0});
      tayaPerson(W/2+Math.sin(t*1.2)*150,470,Math.cos(t*1.2)>0?1:-1,Math.floor(t*8)%2===0,3.4);
      kid(W/2-330+Math.min(t,2)*40,560,0,t,1);kid(W/2+330-Math.min(t,2)*40,560,1,t,-1);
      var a=Math.min(1,t/1.2);ctx.globalAlpha=a;
      txtT('LATA',W/2,300+Math.sin(anim*3)*4,28,'#ffe27a','center');
      txtT('TAYA',W/2+Math.sin(t*1.2)*150,360,24,'#ff7a5a','center');
      txtT('MAMBABATO',W/2-330,455,20,'#8fd0ff','center');
      ctx.globalAlpha=1;
    }else if(s.i===2){
      ctx.fillStyle='rgba(60,20,10,.28)';ctx.fillRect(0,0,W,H);
      var bx=((t*70)%1500)-120;
      RR(bx,480,150,80,8,'#e8b81f',5);RR(bx+90,440,60,50,6,'#c99a10',4);RR(bx+150,500,50,12,3,'#8a8f9c',3);
      person(W/2,520,{scale:3.6,face:-1,step:Math.floor(t*6)%2===0,skin:'#c68a5a',hair:'#222',shirt:'#1b1d2a',stripe:'#ffd23f',pants:'#2b2f4a',hat:'',shoe:'#111'});
      RR(W/2-34,438,68,10,3,'#15161f',4);RR(W/2-22,392,44,50,4,'#15161f',4);
      txtT('DON ALIKABOK',W/2,290,32,'#ff7a5a','center');
      RR(W-380,200,260,130,12,'#3a2a6a',4);txtT('SMART-POD VR',W-250,262,24,'#9ff0ff','center');txtT('ARCADE',W-250,296,22,'#ffd23f','center');
    }else if(s.i===3){
      ctx.fillStyle='rgba(10,14,28,.5)';ctx.fillRect(0,0,W,H);
      var gy=Math.sin(t*2.4)*10;
      ctx.fillStyle='rgba(255,210,63,'+(0.25+0.15*Math.sin(t*5))+')';ctx.beginPath();ctx.arc(W/2,380+gy,150+Math.sin(t*4)*10,0,6.2832);ctx.fill();
      drawCan({x:W/2,y:420+gy,state:'up',fall:0,env:6});
      for(i=0;i<4;i++){var da=t*1.5+i*1.57,dx=W/2+Math.cos(da)*300,dy=300+Math.sin(da)*60;RR(dx-26,dy-10,52,20,8,'#8a8f9c',4);ctx.fillStyle='#ff4a4a';ctx.fillRect(dx-6,dy-3,12,6);}
      txtT('GOLDEN LATA',W/2,250,40,'#ffd23f','center');
    }else if(s.i===5){
      ctx.fillStyle='rgba(10,14,28,.35)';ctx.fillRect(0,0,W,H);
      var ids=['JUAN','MARIA','BERTO','SANTIAGO'];
      for(i=0;i<4;i++){
        var p=clamp((t-0.3-i*0.6)/0.9,0,1),e=1-Math.pow(1-p,3),x=190+i*300,fx=(i%2?W+120:-120);
        var px=fx+(x-fx)*e;
        ctx.globalAlpha=Math.min(1,p*2);
        person(px,520,{face:i<2?1:-1,skin:CHARS[ids[i]].skin,hair:CHARS[ids[i]].hair,shirt:CHARS[ids[i]].shirt,stripe:'#fff',pants:CHARS[ids[i]].pants,hat:CHARS[ids[i]].hat==='salakot'?'salakot':'',band:CHARS[ids[i]].hat==='band'?'#fff':'',step:p<1&&Math.floor(t*10)%2===0,scale:4,ring:'#ffcf8a'});
        txtT(CHARS[ids[i]].name,x,590,20,'#ffffff','center');
        ctx.globalAlpha=1;
      }
    }else{
      ctx.fillStyle='rgba(10,14,28,.5)';ctx.fillRect(0,0,W,H);
      var by=Math.abs(Math.sin(t*3))*30;
      drawCan({x:W/2,y:470-by,state:'up',fall:0});
      txtT('STRIKE D LATA',W/2,260,64,'#f5c542','center');
      txt('SIGAW NG SAKUNA',W/2,330,26,'#ffe0a0','center');
      txt('SDG 3  |  SDG 11  |  SDG 13',W/2,560,20,'#cfe0ff','center',false);
    }
    ctx.restore();
  }
  fade(1-Math.min(t/0.7,1));
  fade(Math.max(0,(t-(len-0.6))/0.6));
  ctx.fillStyle='#05070d';ctx.fillRect(0,0,W,70);ctx.fillRect(0,650,W,70);
  var n=Math.floor(t*36);
  txt(sh.cap.slice(0,n),W/2,686,24,'#fff6dc','center',false);
  for(i=0;i<STORY.length;i++){ctx.fillStyle=i===s.i?'#ffd23f':(i<s.i?'#8a93ab':'#363c56');ctx.fillRect(40+i*34,32,26,6);}
  drawButtons();
}

/* ---------- slide-style How To Play ---------- */
function startHowto(from){scene='HOWTO';howFrom=from;howI=0;howT=0;sel=0;}
function endHowto(){
  if(howFrom==='BOOT'){var f=introAfter;introAfter=null;if(f)f();else{scene='MENU';sel=0;}}
  else{scene='MENU';sel=0;}
}
function howGo(d){var n=HOW.length;if(howI+d<0)return;if(howI+d>=n){endHowto();return;}howI+=d;howT=0;SFX.click();}
function introUpdate(dt){
  if(scene==='STORY'){storyT+=dt;if(storyT>=STORY.reduce(function(a,b){return a+b.len;},0))endStory();}
  else if(scene==='HOWTO')howT+=dt;
  else if(scene==='OUTRO'){OUT.t+=dt;if(OUT.t>=OUT.len)endOutro();}
}
function introKey(code,enter){
  if(scene==='STORY'){if(code==='Escape'||enter||code==='Space')endStory();return;}
  if(scene==='OUTRO'){if(code==='Escape'||enter||code==='Space')endOutro();return;}
  if(code==='ArrowRight'||code==='KeyD'||enter||code==='Space')howGo(1);
  else if(code==='ArrowLeft'||code==='KeyA')howGo(-1);
  else if(code==='Escape')endHowto();
}
function introButtons(sc){
  function B(label,x,y,w,h,act,on){return {label:label,x:x,y:y,w:w,h:h,act:act,on:on};}
  if(sc==='STORY')return [B('SKIP >>',W-200,10,170,50,endStory,false)];
  if(sc==='OUTRO')return [B('SKIP >>',W-200,10,170,50,endOutro,false)];
  var o=[];
  o.push(B('< PREV',60,640,190,54,function(){howGo(-1);},false));
  o.push(B(howI===HOW.length-1?"LET'S PLAY!":'NEXT >',1030,640,190,54,function(){howGo(1);},howI===HOW.length-1));
  o.push(B(howFrom==='BOOT'?'SKIP':'BACK',290,640,160,54,endHowto,false));
  if(howI===HOW.length-1)o.push(B('FULL RULES',810,640,190,54,function(){scene='RULES';sel=0;},false));
  for(var i=0;i<HOW.length;i++)(function(k){o.push({label:'',x:540-HOW.length*14+k*28,y:604,w:24,h:24,act:function(){howI=k;howT=0;},on:false,dot:k});})(i);
  return o;
}
var HOW=[
 {t:'THE GOAL',art:function(x,y,w,h,t){
    drawCan({x:x+w/2,y:y+120,state:'up',fall:0});
    tayaPerson(x+w/2+Math.sin(t*1.4)*100,y+190,Math.cos(t*1.4)>0?1:-1,Math.floor(t*8)%2===0,2.8);
    kid(x+w/2-80,y+h-50,0,t,1);kid(x+w/2+80,y+h-50,1,t,-1);
    txt('KNOCK THE LATA!',x+w/2,y+36,26,'#ffe27a','center');},
  lines:['Hit the lata with your tsinelas (pamato) to knock it down.','Each level needs a number of hits AND a target score before the timer runs out.','Chased and tagged by the Taya? You lose a life.','Beat the villain Don Alikabok: strike the Golden Lata at each biome boss. Earn stars, Barya and Tansan.']},
 {t:'MOVE & SAFE ZONE',art:function(x,y,w,h,t){
    ctx.fillStyle='rgba(80,220,120,.25)';ctx.fillRect(x,y+h-120,w,120);
    ctx.setLineDash([12,8]);ctx.strokeStyle='#fff';ctx.lineWidth=4;ctx.beginPath();ctx.moveTo(x,y+h-120);ctx.lineTo(x+w,y+h-120);ctx.stroke();ctx.setLineDash([]);
    txt('SAFE ZONE',x+w-20,y+h-136,18,'#9dffb0','right');
    kid(x+w/2+Math.sin(t*1.5)*110,y+h-50,0,t,Math.cos(t*1.5)>0?1:-1);
    tayaPerson(x+w/2+Math.sin(t*1.1)*120,y+120,Math.cos(t*1.1)>0?1:-1,Math.floor(t*8)%2===0,2.8);
    keycap(x+60,y+40,'W',34);keycap(x+22,y+78,'A',34);keycap(x+60,y+78,'S',34);keycap(x+98,y+78,'D',34);},
  lines:['P1 moves with W A S D. P2 moves with the arrow keys.','Move freely anywhere on the court.','The safe zone at the bottom is where you throw from, and the Taya cannot catch you there... but she will sprint after you if you step out.','Stay behind the white line to sight and throw.']},
 {t:'SIGHT & THROW',art:function(x,y,w,h,t){
    var hx=x+w/2,hy=y+h-60,ang=Math.sin(t*1.3)*0.5;
    kid(hx,hy,0,t,1);
    ctx.setLineDash([8,8]);ctx.strokeStyle='#ffe27a';ctx.lineWidth=4;ctx.beginPath();ctx.moveTo(hx,hy-30);ctx.lineTo(hx+Math.sin(ang)*230,hy-30-Math.cos(ang)*230);ctx.stroke();ctx.setLineDash([]);
    drawCan({x:x+w/2,y:y+90,state:'up',fall:0});
    RR(x+40,y+h-150,w-80,22,10,'#10131c',3);RR(x+w/2-30,y+h-148,60,18,8,'#46e07a',0);
    var m=Math.sin(t*2.4);RR(x+w/2+m*(w/2-52)-5,y+h-156,10,34,4,'#fff',2);
    keycap(x+70,y+40,'SPACE',84,Math.floor(t*1.5)%2===0);},
  lines:['1. Press SPACE (P2: ENTER) once to start sighting.','2. Press A / D (P2: Left / Right) to turn your aim.','3. Press the key again to throw. Stop the moving marker inside the green zone for a PERFECT throw with bonus points.','You can carry only a few tsinelas, so aim carefully.']},
 {t:'KNOCK IT, WATCH TAYA FETCH IT',art:function(x,y,w,h,t){
    var p=t%9,cx=x+w/2,cy=y+120,c={x:cx,y:cy,state:'up',fall:1,phase:'rest',z:0,rot:0,carried:false,setP:0},tx,ty,face=1,step=false,tx0=cx+70,ty0=cy+70;
    var landX=x+w-70,landY=y+h-120;
    ctx.save();ctx.beginPath();ctx.rect(x,y,w,h);ctx.clip();
    ctx.setLineDash([8,6]);ctx.strokeStyle='rgba(255,255,255,.6)';ctx.lineWidth=3;ctx.beginPath();ctx.ellipse(cx,cy+4,50,16,0,0,6.2832);ctx.stroke();ctx.setLineDash([]);
    tx=tx0;ty=ty0;
    var cap='1. HIT!';
    if(p<1.2){cap='1. YOU HIT THE LATA';}
    else if(p<2.6){var u=(p-1.2)/1.4;c.state='down';c.phase='fly';c.x=cx+(landX-cx)*u;c.y=cy+(landY-cy)*u;c.z=Math.sin(u*Math.PI)*90;c.rot=u*12;cap='2. IT FLIES AWAY';}
    else if(p<4.2){c.state='down';c.phase='rest';c.x=landX;c.y=landY;c.rot=1.45;var u2=(p-2.6)/1.6;tx=tx0+(landX-30-tx0)*u2;ty=ty0+(landY+30-ty0)*u2;step=true;cap='3. TAYA RUNS TO IT';}
    else if(p<5.4){c.state='down';c.phase='rest';c.x=landX;c.y=landY;c.rot=1.45;tx=landX-30;ty=landY+30;cap='4. SHE PICKS IT UP';}
    else if(p<7.2){var u3=(p-5.4)/1.8;tx=landX-30+(cx-(landX-30))*u3;ty=landY+30+((cy+54)-(landY+30))*u3;c.state='down';c.carried=true;c.x=tx;c.y=ty;step=true;face=-1;cap='5. AND CARRIES IT BACK';}
    else if(p<8.6){tx=cx;ty=cy+54;c.state='down';c.phase='set';c.setP=(p-7.2)/1.4;c.x=cx;c.y=cy;cap='6. STANDS IT UP - CHASE RESUMES!';}
    else{tx=cx;ty=cy+54;}
    if(c.state==='down'&&!c.carried&&c.phase==='fly'){}
    var items=[{y:c.y,f:function(){drawCan(c);}},{y:ty,f:function(){tayaPerson(tx,ty,face,step,2.6);}}];
    items.sort(function(a,b){return a.y-b.y;});items.forEach(function(it){it.f();});
    kid(x+60,y+h-40,0,t,1);
    txt(cap,x+w/2,y+h-18,20,'#ffe27a','center');
    ctx.restore();},
  lines:['A hit does not just topple the lata. It is knocked across the court!','The Taya must run over, pick it up, carry it back and stand it on its marker again.','That is YOUR window: while she is busy, run out, grab your tsinelas and set up your next throw.','Once the lata is upright again, she comes after you.']},
 {t:'PICK-UPS & TRAPS',art:function(x,y,w,h,t){
    var keys=Object.keys(POGS),i;
    for(i=0;i<3;i++)drawPog(keys[i],x+100+i*150,y+90+Math.sin(t*3+i)*8,26,t*2);
    for(i=0;i<4;i++)coinIcon(x+70+i*50,y+170+Math.sin(t*4+i)*5,14);
    RR(x+50,y+h-120,w-100,40,10,'#3a7bd5',3);txt('PUDDLE / OBSTACLE',x+w/2,y+h-100,16,'#fff','center',false);
    kid(x+w/2,y+h-40,0,t,1);},
  lines:['Walk over your thrown tsinelas to pick them up again. You have a limited supply!','Collect pogs for power-ups, and coins for the Tsinelas shop.','Puddles slow you down. Bikes, bins and crowds block the path.','Run out of tsinelas and you fail the level, so always retrieve them.']},
 {t:'DISASTERS & CO-OP',art:function(x,y,w,h,t){
    var ty=['FLOOD','AMIHAN','EARTHQUAKE','BROWNOUT','ASHFALL'],k=Math.floor(t/2)%5;
    drawDisasterScene(x,y,w,h,ty[k]);
    txtT(HAZ[ty[k]].label,x+w/2,y+36,30,HAZ[ty[k]].col,'center');},
  lines:['Each biome introduces a new disaster on its first level, and earlier ones return later. No warnings mid-game, so stay alert!','Press E (P1) or Right Shift (P2) for your hero skill.','2 players: P1 = WASD + SPACE, P2 = Arrows + ENTER. Endless co-op has coin upgrades.','Play for SDG 3 (safety), SDG 11 (resilient communities) and SDG 13 (climate action).']}];
function drawHowto(){
  drawWood();
  var sl=HOW[howI],i;
  txtT('HOW TO PLAY',W/2,40,30,'#ffd23f','center');
  panel(50,70,1180,522,'rgba(30,35,52,.95)');
  txt(String(howI+1)+' / '+HOW.length+'   '+sl.t,90,118,36,'#ffd23f','left');
  var ax=90,ay=150,aw=500,ah=400;
  ctx.save();ctx.beginPath();ctx.rect(ax,ay,aw,ah);ctx.clip();
  ctx.drawImage(bgFor(howI===5?3:0),340,180,520,420,ax,ay,aw,ah);
  ctx.fillStyle='rgba(10,14,28,.15)';ctx.fillRect(ax,ay,aw,ah);
  sl.art(ax,ay,aw,ah,howT);
  ctx.restore();
  RR(ax,ay,aw,ah,12,'rgba(0,0,0,0)',4);
  var y=186,k;
  for(i=0;i<sl.lines.length;i++){
    var ls=wrap(sl.lines[i],520,22);
    circ(640,y-2,5,'#ffd23f',0);
    for(k=0;k<ls.length;k++){txt(ls[k],660,y,22,'#fff6dc','left',false);y+=30;}
    y+=16;
  }
  var bs=introButtons('HOWTO');
  for(i=0;i<bs.length;i++){
    var b=bs[i];
    if(b.dot!==undefined){circ(b.x+12,b.y+12,b.dot===howI?9:6,b.dot===howI?'#ffd23f':'#5a6382',2);continue;}
    drawBtn(b,i===sel||inRect(mouse,b));
  }
}

/* ---------- home page: heroes + disasters ---------- */
var HOME_DIS=['FLOOD','AMIHAN','EARTHQUAKE','BROWNOUT','ASHFALL'],HOME_LAB={EARTHQUAKE:'LINDOL / LANDSLIDE'},HOME_CH=['JUAN','MARIA','BERTO','SANTIAGO'];
function menuButtons(){
  function B(label,x,y,w,h,act,on){return {label:label,x:x,y:y,w:w,h:h,act:act,on:on};}
  function IB(icon,label,x,act){return {icon:icon,label:label,x:x,y:590,w:78,h:78,act:act,on:false};}
  var o=[],i;
  o.push({label:'PROFILE',x:20,y:14,w:330,h:64,on:false,draw:function(b,hot){if(hot||!PROF){ctx.strokeStyle=hot?'#ffd23f':'rgba(255,210,63,'+(0.4+0.4*Math.sin(anim*5))+')';ctx.lineWidth=4;rrect(b.x-2,b.y-2,b.w+4,b.h+4,18);ctx.stroke();}txt(PROF?'CHANGE PROFILE':'CLICK TO PICK',b.x+b.w-14,b.y+b.h-14,11,'#ffcf8a','right',false);},act:function(){scene='PROFILES';sel=0;delConfirm=false;for(var q=0;q<DB.profiles.length;q++)if(PROF&&DB.profiles[q]===PROF)pSel=q;pOff=Math.max(0,pSel-4);}});
  o.push(B('PLAY GAME',340,396,600,100,function(){goMode();},true));
  var ic=[['shop','TSINELAS SHOP',function(){openShop('MENU');}],['rules','HOW TO PLAY',function(){startHowto('MENU');}],['pog','POG CATALOG',function(){scene='CAT_POG';sel=0;}],['disaster','DISASTERS',function(){scene='CAT_DIS';sel=0;}],['profile','PROFILES',function(){scene='PROFILES';sel=0;delConfirm=false;}],['chars','HEROES',function(){if(needProf())return;scene='CHARS';sel=0;}],['trophy','LEADERBOARD',function(){openBoard();}],[musicOn?'music':'musicoff',musicOn?'MUSIC: ON':'MUSIC: OFF',function(){musicOn=!musicOn;}]];
  for(i=0;i<ic.length;i++)o.push(IB(ic[i][0],ic[i][1],265+i*96,ic[i][2]));
  return o;
}
function CHAR_ORDER_IDX(id){var ks=Object.keys(CHARS);var i=ks.indexOf(id);return i<0?0:i;}
function drawSlotTab(b,hot){
  var on=charSlot===b.slot;
  RR(b.x,b.y,b.w,b.h,10,on?'#ffd23f':(hot?'#e8873a':'#363c56'),3);
  txt('FOR '+b.label,b.x+b.w/2,b.y+b.h/2,15,on?'#1d1b2e':'#fff','center',!on);
}
function drawHeroCard(b,hot){
  var id=b.cid,c=CHARS[id],owned=SAVE.chars.indexOf(id)>=0,x=b.x,y=b.y,w=b.w,h=b.h,used=SAVE.sel[charSlot]===id;
  RR(x,y+5,w,h,16,'rgba(0,0,0,.4)',0);
  RR(x,y-(hot?3:0),w,h,16,used?'#2f6f4a':(hot?'#4a5276':'#30364e'),used?5:3.5);
  var py=y-(hot?3:0);
  RR(x+8,py+8,w-16,170,10,'#1f2538',0);
  ctx.save();ctx.beginPath();ctx.rect(x+8,py+8,w-16,170,10);ctx.clip();
  portrait(id,x+w/2,py+160+(hot?Math.sin(anim*8)*2:0),3.6);
  if(!owned){ctx.fillStyle='rgba(10,12,20,.62)';ctx.fillRect(x+8,py+8,w-16,170);lockIcon(x+w/2,py+96,1.6);}
  ctx.restore();
  txt(c.name,x+w/2,py+196,17,'#ffffff','center');
  var act=c.skills[2];
  var an=wrap(act.n==='SUMPIT SMOKE / TEKS DECOY'?'SUMPIT SMOKE':act.n,w-12,15),ay=py+(an.length>1?218:226);
  for(var aq=0;aq<an.length&&aq<2;aq++)txt(an[aq],x+w/2,ay+aq*17,15,'#f5c542','center',false);
  txt('ACTIVE SKILL',x+w/2,py+252,11,'#a8b4c8','center',false);
  var lab=!owned?('UNLOCK '+c.cost):(used?'IN USE':'SELECT');
  RR(x+14,py+h-48,w-28,34,10,!owned?'#d9742a':(used?'#2fcf6b':'#566079'),3);
  txt(lab,x+w/2,py+h-31,15,'#fff','center');
  for(var s=0;s<2;s++)if(SAVE.sel[s]===id){RR(x+8+s*34,py+10,30,20,8,s?'#ffc533':'#38b6ff',2);txt('P'+(s+1),x+23+s*34,py+20,12,'#10131c','center',false);}
}
function drawDisTile(b,hot){
  var tp=b.dz,x=b.x,y=b.y,w=b.w,h=b.h;
  RR(x,y+5,w,h,14,'rgba(0,0,0,.4)',0);
  RR(x,y,w,h,14,'#1f2538',3);
  drawDisasterScene(x+4,y+4,w-8,h-8,tp);
  RR(x+4,y+h-44,w-8,40,10,'rgba(12,15,24,.82)',0);
  txt(HOME_LAB[tp]||HAZ[tp].label,x+16,y+h-30,19,HAZ[tp].col,'left');
  txt('FIRST SEEN: '+ENV_NAMES[hazBiome(tp)],x+16,y+h-12,12,'#cfd6e6','left',false);
  RR(x,y,w,h,14,'rgba(0,0,0,0)',hot?6:3.5);
  if(hot){ctx.strokeStyle='#ffd23f';ctx.lineWidth=3;ctx.strokeRect(x+3,y+3,w-6,h-6);}
}
function drawMenu(){
  drawWood();
  var bob=Math.sin(anim*2)*3,cx=640,i;
  sign(cx-540,96+bob*0.4,1080,210);
  txtT('STRIKE D LATA',cx,190+bob,80,'#f5c542','center');
  txt('SIGAW NG SAKUNA',cx,262+bob*0.3,28,'#ffe0a0','center',false);
  RR(20,14,330,64,16,'rgba(30,35,52,.92)',3);
  txt(PROF?SAVE.name:'CHOOSE PROFILE',34,34,PROF?22:18,PROF?'#9dffb0':'#ffd23f','left');txt(players===2?'CO-OP SAVE':'SOLO SAVE',336,34,11,'#ffcf8a','right',false);
  drawStar(46,60,10,true);txt(totalStars()+'/150',62,60,16,'#ffe66a','left',false);coinIcon(160,60,10);txt(String(SAVE.coins),176,60,16,'#ffe27a','left',false);
  txt('Tumbahin ang lata, iligtas ang laro!',cx,356,22,'#cfe0ff','center',false);
  drawButtons();
  txt('P1: WASD + SPACE (skill E)     P2: ARROWS + ENTER (skill R-SHIFT)     keys: R how-to  G pogs  D disasters  C heroes  B board',cx,712,13,'#a8b4c8','center',false);
}

/* ---------- Level 1 interactive tutorial (centered on screen) ---------- */
var TUT_STEPS=[
 {k:'GOAL',t:'HOW TO WIN LEVEL 1',modal:1},
 {k:'POSITION',t:'1. GET INTO POSITION',d:'Walk to the glowing marker with W A S D. Stay inside the safe zone at the bottom of the court.',keys:['W','A','S','D']},
 {k:'SIGHT',t:'2. START SIGHTING',d:'Press SPACE once to start sighting. A dotted line shows where your tsinelas will fly.',keys:['SPACE']},
 {k:'AIM',t:'3. TURN YOUR AIM',d:'Press A or D to swing the dotted line until it points at the lata.',keys:['A','D']},
 {k:'THROW',t:'4. THROW!',d:'Press SPACE again to throw. Stop the marker in the green zone for a PERFECT throw.',keys:['SPACE']},
 {k:'WAIT',t:'5. HIT OR MISS?',d:'',keys:[]},
 {k:'WATCH',t:'6. PAK! THE LATA FLIES',d:'Now the robot Taya must run to the lata, pick it up and carry it back. Use that time to grab your tsinelas!',keys:[]},
 {k:'RETRIEVE',t:'7. GET YOUR TSINELAS',d:'Walk over your tsinelas on the ground to pick them up. Once the lata is back up, the Taya chases at once!',keys:[]},
 {k:'READY',t:'YOU ARE READY!',modal:1,keys:['ENTER']}];
function tutIdx(k){for(var i=0;i<TUT_STEPS.length;i++)if(TUT_STEPS[i].k===k)return i;return 0;}
function tutGo(k){G.tut.i=tutIdx(k);G.tut.t=0;SFX.click();}
function tutBegin(){
  if(!G||G.level!==1||G.two||G.endless||tutSeen)return;
  tutSeen=true;
  var h=G.hitters[0];
  G.tut={i:0,t:0,mx:CAN_X-190,my:G.line+46,aim0:0,miss:false};
  G.banner=null;h.x=CAN_X+130;h.y=G.line+46;
}
function tutFinish(){
  G.tut=null;tutSeen=true;
  startLevel(1);G.brief=null;scene='PLAY';SFX.start();
}
function tutKey(code,enter){
  var T=G.tut;if(!T)return false;
  if(code==='Escape'){tutFinish();return true;}
  var k=TUT_STEPS[T.i].k;
  if(k==='GOAL'){if(enter||code==='Space')tutGo('POSITION');return true;}
  if(k==='READY'){if(enter||code==='Space')tutFinish();return true;}
  if(k==='POSITION'&&(code==='Space'||enter))return true;
  if(k==='THROW'&&code==='Space')G.hitters[0].meter.m=0;
  return false;
}
function tutUpdate(dt){
  var T=G.tut,h=G.hitters[0],st=TUT_STEPS[T.i].k;
  G.time+=dt;T.t+=dt;
  G.pogs=[];G.coins=[];G.clearing=false;G.hits=0;
  updateHitters(dt);updateSlippers(dt);updateCan(dt);
  if(G.can.state==='down')updateTaya(dt);
  updateFx(dt);
  if(scene!=='PLAY'||!G.tut)return;
  var up=(G.can.state==='up'),fly=false,j;
  for(j=0;j<G.slippers.length;j++)if(G.slippers[j].state==='fly')fly=true;
  h.stun=0;
  if(st==='GOAL'||st==='READY'){h.ammo=h.total;h.meter.on=false;}
  else if(st==='POSITION'){h.ammo=h.total;if(Math.hypot(h.x-T.mx,h.y-T.my)<40)tutGo('SIGHT');}
  else if(st==='SIGHT'){h.ammo=h.total;if(h.meter.on){T.aim0=h.aim;tutGo('AIM');}}
  else if(st==='AIM'){h.ammo=h.total;if(!h.meter.on)tutGo('SIGHT');else if(Math.abs(h.aim-T.aim0)>0.06)tutGo('THROW');}
  else if(st==='THROW'){if(!h.meter.on&&(fly||G.slippers.length))tutGo('WAIT');}
  else if(st==='WAIT'){
    if(G.can.state==='down')tutGo('WATCH');
    else if(!fly&&T.t>0.6){T.miss=true;if(T.t>1.2)tutGo('RETRIEVE');}
  }
  else if(st==='WATCH'){if(up&&T.t>0.5)tutGo(h.ammo<h.total?'RETRIEVE':'READY');}
  else if(st==='RETRIEVE'){if(h.ammo>=h.total&&up)tutGo(T.miss?'SIGHT':'READY');}
}
function hms(tm){var m=Math.floor(tm/60),s=tm%60;return m+':'+(s<10?'0':'')+s;}
function drawTut(){
  var T=G.tut;if(!T)return;
  var s=TUT_STEPS[T.i],pu=0.5+0.5*Math.sin(anim*6),i,ls,yy;
  if(s.k==='POSITION'){
    ctx.save();ctx.translate(AX,0);
    ctx.strokeStyle='rgba(255,226,90,'+(0.5+pu*0.5)+')';ctx.lineWidth=5;ctx.setLineDash([10,7]);ctx.beginPath();ctx.ellipse(T.mx,T.my+6,40+pu*6,16+pu*3,0,0,6.2832);ctx.stroke();ctx.setLineDash([]);
    txt('GO HERE',T.mx,T.my-34,16,'#ffe27a','center');
    ctx.restore();
  }
  if(s.k==='AIM'||s.k==='THROW'||s.k==='WAIT'||s.k==='SIGHT'){
    ctx.save();ctx.translate(AX,0);
    ctx.strokeStyle='rgba(255,226,90,'+(0.4+pu*0.6)+')';ctx.lineWidth=4;ctx.beginPath();ctx.ellipse(G.can.x,G.can.y+4,44+pu*6,16+pu*3,0,0,6.2832);ctx.stroke();
    txt('THE LATA',G.can.x,G.can.y-70,18,'#ffe27a','center');
    ctx.restore();
  }
  if(s.k==='WATCH'||s.k==='RETRIEVE'){
    ctx.save();ctx.translate(AX,0);
    txt('TAYA IS BUSY!',G.taya.x,G.taya.y-130,18,'#ff9a8a','center');
    ctx.restore();
  }
  var cx=W/2,tot=TUT_STEPS.length;
  if(s.modal){
    ctx.fillStyle='rgba(6,8,16,.62)';ctx.fillRect(0,0,W,H);
    var mw=640,mh=s.k==='GOAL'?392:300,mx=cx-mw/2,my=H/2-mh/2;
    panel(mx,my,mw,mh,'rgba(24,28,44,.97)');
    txt(s.t,cx,my+44,32,'#ffd23f','center');
    var need=G.cfg.hits,goal=G.goal,tm=Math.round(G.cfg.timeLimit);
    var rows=[['KNOCK THE LATA',need+' times'],['SCORE AT LEAST',goal+' points'],['BEFORE TIME RUNS OUT',hms(tm)],['DO NOT LOSE ALL LIVES','or run out of tsinelas']];
    if(s.k==='GOAL'){
      txt('The robot Taya guards the Golden Lata signal. Strike it down!',cx,my+82,17,'#ffcf8a','center',false);
      for(i=0;i<rows.length;i++){var ry=my+128+i*52;RR(mx+36,ry-20,mw-72,42,10,'#2f3652',2.5);txt(rows[i][0],mx+54,ry,18,'#cfd6e6','left',false);txt(rows[i][1],mx+mw-54,ry,20,'#ffe27a','right');}
      txt('This short tutorial walks you through it.  ESC = skip',cx,my+mh-76,15,'#a8b4c8','center',false);
    }else{
      txt('Hit the lata '+need+' times, reach '+goal+' points, and finish in '+hms(tm)+'.',cx,my+92,19,'#fff6dc','center',false);
      txt('Retrieve your tsinelas, stay in the safe zone, and watch out for the Taya!',cx,my+124,17,'#cfd6e6','center',false);
    }
    var bx=cx-150,by=my+mh-58;RR(bx,by,300,42,12,'#2fcf6b',3);
    txt(s.k==='GOAL'?'PRESS ENTER / SPACE':'PRESS ENTER TO START',cx,by+21,18,'#fff','center');
    for(i=0;i<tot;i++)circ(cx-(tot-1)*10+i*20,my+mh+22,i===T.i?6:4,i<=T.i?'#ffd23f':'#5a6382',2);
    return;
  }
  var body=s.d;
  if(s.k==='WAIT')body=T.miss?'MISSED! No worries. Pick up your tsinelas and try again.':'Watch your tsinelas fly...';
  ls=wrap(body,520,19);
  var cw=600,ch=92+ls.length*24+(s.keys.length?44:0),cy=372-ch/2;
  ctx.globalAlpha=0.9;panel(cx-cw/2,cy,cw,ch,'rgba(24,28,44,.97)');ctx.globalAlpha=1;
  txt('TUTORIAL  '+T.i+'/'+(tot-2),cx-cw/2+20,cy+24,13,'#a8b4c8','left',false);
  txt('ESC = skip',cx+cw/2-20,cy+24,13,'#a8b4c8','right',false);
  txt(s.t,cx,cy+54,26,'#ffd23f','center');
  yy=cy+86;
  for(i=0;i<ls.length;i++){txt(ls[i],cx,yy,19,'#fff6dc','center',false);yy+=24;}
  if(s.keys.length){
    var tw=0,kws=[];for(i=0;i<s.keys.length;i++){var kw=s.keys[i].length>1?(s.keys[i].length*10+22):34;kws.push(kw);tw+=kw+8;}
    var kx=cx-tw/2+4;
    for(i=0;i<s.keys.length;i++){keycap(kx+kws[i]/2,yy+14,s.keys[i],kws[i],Math.floor(anim*2)%2===0);kx+=kws[i]+8;}
  }
  for(i=0;i<tot;i++)circ(cx-(tot-1)*10+i*20,cy+ch+18,i===T.i?6:4,i<=T.i?'#ffd23f':'#5a6382',2);
}

/* ---------- boss-defeat short story (one per biome) ---------- */
var OUT={b:0,t:0,len:13.2};
var OUTRO_CAPS=[
 ['The Rogue Jeepney-Bot stalls in the flood. Its Golden Lata is knocked loose!','The Golden Lata cracks and goes dark. Drones drop out of the sky over Kalye Uno.','Don Alikabok: "Impossible! Fine... the beaches will be next!"'],
 ['The Mechanical Bakunawa sinks into the tide. Its Golden Lata sputters out!','The amihan calms. Fishers steer their banca safely back to shore.','Don Alikabok: "My Smart-Pods will rise on the rice terraces!"'],
 ['The Kalabaw-Bot 3000 topples. The Golden Lata rolls off its back and cracks!','The farmers cheer as the palayan stops shaking and the drones fall.','Don Alikabok: "Then I will switch off the lights at the fiesta!"'],
 ['The Ferris-Bot grinds to a halt. Its Golden Lata flickers and dies!','The Perya lights blaze back on. Children cheer under the ferris wheel.','Don Alikabok: "My last beacon burns inside the volcano!"'],
 ['The Mayon Magma-Bot melts down. The last Golden Lata shatters!','Every drone falls. The Smart-Pod arcades power down all over the country.','Don Alikabok is beaten. The kalye is ours again. Tumbang Preso lives on!']];
function startOutro(b){OUT.b=b;OUT.t=0;scene='OUTRO';sel=0;SFX.clear&&SFX.clear();}
function endOutro(){scene='LEVEL_CLEAR';sel=0;celebrateInit();}
function drawOutro(){
  var b=OUT.b,t=OUT.t,L=OUT.len/3,i=Math.min(2,Math.floor(t/L)),st=t-i*L,d=BOSSES[b],k,q;
  ctx.save();ctx.beginPath();ctx.rect(0,70,W,580);ctx.clip();
  var z=1+0.05*(st/L);ctx.translate(W/2,H/2);ctx.scale(z,z);ctx.translate(-W/2,-H/2);
  ctx.drawImage(bgFor(b),0,0,W,H);
  if(i===0){
    ctx.fillStyle='rgba(40,10,10,.22)';ctx.fillRect(0,0,W,H);
    var shake=st<1.6?Math.sin(st*60)*(4-st*2):0,fall=clamp((st-1.4)/1.6,0,1),sink=fall*50;
    ctx.save();ctx.translate(W/2-120+shake,520+sink);ctx.rotate(fall*0.5);
    ctx.save();ctx.scale(2.3,2.3);drawBossRig(d,true,t);ctx.restore();
    tayaPerson(0,0,1,false,3.8);
    ctx.save();ctx.scale(2.3,2.3);drawBossRig(d,false,t);ctx.restore();
    ctx.restore();
    var lp=clamp((st-1.2)/1.6,0,1),lx=W/2-120+lp*260,ly=380-Math.sin(lp*3.1416)*200+lp*130;
    ctx.save();ctx.translate(lx,ly);ctx.rotate(lp*9);drawCan({x:0,y:0,state:'up',fall:0,env:6});ctx.restore();
    for(k=0;k<6;k++){var pf=clamp((st-1.4)/2.4,0,1);ctx.globalAlpha=1-pf;ctx.fillStyle='#c9c9d2';ctx.beginPath();ctx.arc(W/2-120+Math.sin(k*2.1)*70,470-pf*160-k*8,18+pf*30,0,6.2832);ctx.fill();}
    ctx.globalAlpha=1;
    if(st>1.5){var pop=Math.min(1,(st-1.5)/0.3);ctx.save();ctx.translate(W/2+160,300);ctx.rotate(-0.12);ctx.scale(pop,pop);txtT('K.O.!',0,0,72,'#ffd23f','center');ctx.restore();}
    for(k=0;k<2;k++)kid(150+k*90,570,k,t,1);
  }else if(i===1){
    ctx.fillStyle='rgba(10,14,28,.45)';ctx.fillRect(0,0,W,H);
    var dim=clamp(1-st/2,0,1),cx2=W/2,cy2=400;
    ctx.fillStyle='rgba(255,210,63,'+(0.35*dim)+')';ctx.beginPath();ctx.arc(cx2,cy2,170*dim+20,0,6.2832);ctx.fill();
    ctx.save();ctx.translate(cx2,cy2);ctx.rotate(Math.sin(st*3)*0.05);drawCan({x:0,y:0,state:'up',fall:0,env:6});
    ctx.strokeStyle='#14121c';ctx.lineWidth=4;ctx.beginPath();ctx.moveTo(-6,-60);ctx.lineTo(4,-30);ctx.lineTo(-8,-6);ctx.lineTo(6,22);ctx.stroke();ctx.restore();
    for(k=0;k<4;k++){var dx=190+k*300,dy=170+clamp(st*0.6-k*0.1,0,1.2)*330;
      ctx.save();ctx.translate(dx,dy);ctx.rotate(Math.sin(st*5+k)*0.4);RR(-26,-10,52,20,8,'#8a8f9c',4);ctx.fillStyle=st>1.2?'#3a0a0a':'#ff4a4a';ctx.fillRect(-6,-3,12,6);ctx.restore();
      if(dy>400){ctx.globalAlpha=.5;ctx.fillStyle='#ffd23f';for(q=0;q<5;q++)ctx.fillRect(dx-30+q*14,540-Math.abs(Math.sin(st*8+q))*20,6,6);ctx.globalAlpha=1;}}
    var jump=Math.abs(Math.sin(st*5))*18;
    for(k=0;k<4;k++){var cc=CHARS[['JUAN','MARIA','BERTO','SANTIAGO'][k]];
      person(260+k*250,590-jump*(k%2?1:.6),{face:k<2?1:-1,skin:cc.skin,hair:cc.hair,shirt:cc.shirt,stripe:'#fff',pants:cc.pants,hat:cc.hat==='salakot'?'salakot':'',step:Math.floor(st*8)%2===0,scale:3.1});}
    txtT('GOLDEN LATA DOWN!',W/2,200,44,'#ffd23f','center');
  }else{
    ctx.fillStyle='rgba(60,20,10,.25)';ctx.fillRect(0,0,W,H);
    var fin=(b===4);
    if(fin){
      ctx.fillStyle='rgba(255,230,150,'+(0.1+0.15*Math.sin(st*3))+')';ctx.fillRect(0,0,W,H);
      for(k=0;k<4;k++){var cc2=CHARS[['JUAN','MARIA','BERTO','SANTIAGO'][k]];
        person(250+k*250,580-Math.abs(Math.sin(st*5+k))*16,{face:k<2?1:-1,skin:cc2.skin,hair:cc2.hair,shirt:cc2.shirt,stripe:'#fff',pants:cc2.pants,hat:cc2.hat==='salakot'?'salakot':'',step:Math.floor(st*8)%2===0,scale:3.1});}
      drawCan({x:W/2,y:470,state:'up',fall:0,env:b});
      txtT('THE END',W/2,230,64,'#ffd23f','center');
    }else{
      var vx=W/2+260,sh2=Math.sin(st*14)*3;
      person(vx+sh2,530,{scale:3.6,face:-1,step:Math.floor(st*6)%2===0,skin:'#c68a5a',hair:'#222',shirt:'#1b1d2a',stripe:'#ffd23f',pants:'#2b2f4a',hat:'',shoe:'#111'});
      RR(vx-34+sh2,448,68,10,3,'#15161f',4);RR(vx-22+sh2,402,44,50,4,'#15161f',4);
      txtT('DON ALIKABOK',vx,330,24,'#ff7a5a','center');
      for(k=0;k<2;k++)kid(180+k*120,570,k,t,1);
      kid(420,570,2,t,1);
      if(st>0.6){var bw=clamp((st-0.6)/0.4,0,1);ctx.save();ctx.translate(vx-190,260);ctx.scale(bw,bw);RR(-130,-34,260,68,14,'#fff6dc',3);txt('GRRR... NEXT TIME!',0,0,20,'#3a1a10','center',false);ctx.restore();}
    }
  }
  ctx.restore();
  fade(1-Math.min(st/0.6,1));fade(Math.max(0,(st-(L-0.5))/0.5));
  ctx.fillStyle='#05070d';ctx.fillRect(0,0,W,70);ctx.fillRect(0,650,W,70);
  txt(BOSSES[b].name+' DEFEATED',W/2,36,22,'#ffd23f','center');
  var n=Math.floor(st*36);txt(OUTRO_CAPS[b][i].slice(0,n),W/2,686,22,'#fff6dc','center',false);
  for(k=0;k<3;k++){ctx.fillStyle=k===i?'#ffd23f':(k<i?'#8a93ab':'#363c56');ctx.fillRect(40+k*34,58,26,6);}
  drawButtons();
}

/* ---------- homepage: all four heroes vs the robot Taya bosses (animation only) ---------- */
function drawHomeBattle(x,y,w,h){
  var bi=Math.floor(anim/7)%5,d=BOSSES[bi],ph=(anim%7)/7,k,gy=y+h-70;
  ctx.save();ctx.beginPath();ctx.rect(x,y,w,h);ctx.clip();
  ctx.drawImage(bgFor(bi),0,150,1280,390,x,y,w,h);
  if(bi===3||bi===4){ctx.fillStyle='rgba(10,10,30,.25)';ctx.fillRect(x,y,w,h);}
  var ids=['JUAN','MARIA','BERTO','SANTIAGO'],ex=x+w-230,ey=gy;
  /* drones */
  for(k=0;k<3;k++){var dx=ex-150+k*120+Math.sin(anim*1.6+k)*18,dy=y+60+Math.sin(anim*2.2+k*2)*14+(k===1?20:0);
    RR(dx-24,dy-9,48,18,7,'#8a8f9c',3);ctx.fillStyle=Math.floor(anim*3+k)%2?'#ff4a4a':'#7a1010';ctx.fillRect(dx-5,dy-3,10,6);
    ctx.strokeStyle='#14121c';ctx.lineWidth=3;ctx.beginPath();ctx.moveTo(dx-28,dy-12);ctx.lineTo(dx-12,dy-12);ctx.moveTo(dx+12,dy-12);ctx.lineTo(dx+28,dy-12);ctx.stroke();}
  /* weather hint per biome */
  ctx.strokeStyle=bi===0?'rgba(180,220,255,.55)':bi===1?'rgba(255,255,255,.4)':'rgba(255,200,120,.35)';ctx.lineWidth=2;
  if(bi<2||bi===4)for(k=0;k<22;k++){var rx=x+((k*97+anim*(bi===1?260:90))%w),ry=y+((k*53+anim*(bi===0?420:70))%h);ctx.beginPath();ctx.moveTo(rx,ry);ctx.lineTo(rx-(bi===1?26:6),ry+(bi===1?3:14));ctx.stroke();}
  /* hero throw cycle */
  var cyc=anim/0.95,hi=Math.floor(cyc)%4,ft=cyc%1,hit=ft>0.88||ft<0.12;
  var hurt=(ft<0.22&&anim>1)?Math.sin(anim*70)*3:0;
  /* enemy: mechanised taya + golden lata */
  ctx.save();ctx.translate(ex+hurt,ey);
  shadowEll(0,4,100,18,.35);
  ctx.save();ctx.scale(2.2,2.2);drawBossRig(d,true,anim);ctx.restore();
  tayaPerson(0,0,-1,Math.floor(anim*6)%2===0,3.6);
  ctx.save();ctx.scale(2.2,2.2);drawBossRig(d,false,anim);ctx.restore();
  ctx.save();ctx.translate(38,-175);ctx.rotate(ft<0.3?Math.sin(anim*40)*.18:0);drawCan({x:0,y:0,state:'up',fall:0,env:6});ctx.restore();
  ctx.restore();
  /* heroes */
  for(k=0;k<4;k++){
    var c=CHARS[ids[k]],hx=x+110+k*150,jump=(k===hi&&ft<0.2)?Math.sin(ft/0.2*3.14)*14:0,off=0;
    if(k===1&&k===hi)off=Math.min(1,ft*3)*30;
    if(k===1&&k===hi&&ft<0.5){ctx.strokeStyle='rgba(255,255,255,.6)';ctx.lineWidth=3;for(var q=0;q<3;q++){ctx.beginPath();ctx.moveTo(hx-30-q*12,gy-60+q*14);ctx.lineTo(hx-70-q*12,gy-60+q*14);ctx.stroke();}}
    person(hx+off,gy-jump+(k%2)*10,{face:1,skin:c.skin,hair:c.hair,shirt:c.shirt,stripe:'#fff',pants:c.pants,hat:c.hat==='salakot'?'salakot':'',step:Math.floor(anim*5+k)%2===0,scale:3.5});
    txtT(c.name,hx,gy+34+(k%2)*10,14,'#ffffff','center');
  }
  /* projectile + skill fx */
  var hx0=x+110+hi*150+30+(hi===1?Math.min(1,ft*3)*30:0),hy0=gy-110,tx=ex+38-10,ty=gy-215;
  if(ft<0.88){
    var u=ft/0.88,px=hx0+(tx-hx0)*u,py=hy0+(ty-hy0)*u-Math.sin(u*3.14)*(hi===0?40:90);
    if(hi===0){py=hy0+(ty-hy0)*u-Math.abs(Math.sin(u*3.14*2))*70;}
    ctx.save();ctx.translate(px,py);slipperShape(anim*14,false,2.2);ctx.restore();
    if(hi===2&&u>0.4){ctx.fillStyle='rgba(220,224,236,'+(0.6-u*0.4)+')';for(k=0;k<4;k++){ctx.beginPath();ctx.arc(ex-120+k*26,gy-30-k*5,20+u*18,0,6.2832);ctx.fill();}}
    if(hi===3&&u>0.3){ctx.strokeStyle='rgba(255,210,63,'+(1-u)+')';ctx.lineWidth=5;ctx.beginPath();ctx.ellipse(x+110+3*150,gy+6,40+u*260,10+u*30,0,0,6.2832);ctx.stroke();}
  }
  if(ft>0.88||ft<0.2){var pf=ft>0.88?(ft-0.88)/0.32:(ft+0.12)/0.32;ctx.save();ctx.translate(tx-10,ty-40);ctx.rotate(-0.15);ctx.scale(0.7+pf*0.5,0.7+pf*0.5);txtT(['PAK!','BLAG!','KLANG!','BOOM!'][hi],0,0,38,'#ffd23f','center');ctx.restore();}
  ctx.restore();
  RR(x+w-470,y+10,456,40,12,'rgba(12,15,24,.8)',3);
  txtT('VS  '+d.name,x+w-242,y+31,22,d.col,'center');
  RR(x,y,w,h,14,'rgba(0,0,0,0)',4);
}

/* ---------- PLAY GAME -> choose mode ---------- */
function goMode(){if(needProf())return;scene='MODE';sel=players===2?1:0;}
function modeButtons(){
  function B(label,x,y,w,h,act,on){return {label:label,x:x,y:y,w:w,h:h,act:act,on:on};}
  return [
    B('CLASSIC  -  1 PLAYER',290,190,700,110,function(){setPlayers(1);openSelect();},players===1),
    B('ENDLESS  -  2 PLAYER CO-OP',290,350,700,110,function(){setPlayers(2);openSelect();},players===2),
    B('BACK',490,540,300,60,function(){scene='MENU';sel=0;},false)];
}
function drawMode(){
  drawWood();
  txtT('CHOOSE YOUR GAME',640,96,56,'#f5c542','center');
  txt('50 levels across 5 biomes, one hero, one Taya at a time',640,312,18,'#cfe0ff','center',false);
  txt('Survive wave after wave with a partner and pick coin upgrades',640,472,18,'#cfe0ff','center',false);
  drawButtons();
}

""";

    private static final String JS_AUDIO = """

/* =========================================================================
   AUDIO - WebAudio synth: sound effects + a dynamic step-sequenced BGM that changes
   tempo and instrumentation with the biome, active disaster and chase scenes.
   ========================================================================= */
var AC=null,master=null,noiseBuf=null;
function ac(){
  if(!AC){
    try{
      AC=new (window.AudioContext||window.webkitAudioContext)();
      master=AC.createGain();master.gain.value=0.9;master.connect(AC.destination);
      noiseBuf=AC.createBuffer(1,AC.sampleRate*1.5,AC.sampleRate);
      var d=noiseBuf.getChannelData(0);for(var i=0;i<d.length;i++)d[i]=Math.random()*2-1;
    }catch(e){AC=null;}
  }
  if(AC&&AC.state==='suspended')AC.resume();
  return AC;
}
window.addEventListener('keydown',ac);
window.addEventListener('mousedown',ac);
function tone(f,d,type,vol,f2,delay,dest){
  if(muted)return;
  var a=ac();if(!a)return;
  try{
    var t0=a.currentTime+(delay||0);
    var o=a.createOscillator(),g=a.createGain();
    o.type=type||'square';
    o.frequency.setValueAtTime(f,t0);
    if(f2)o.frequency.exponentialRampToValueAtTime(f2,t0+d);
    g.gain.setValueAtTime(vol||0.06,t0);
    g.gain.exponentialRampToValueAtTime(0.0001,t0+d);
    o.connect(g);g.connect(dest||master);
    o.start(t0);o.stop(t0+d+0.02);
  }catch(e){}
}
function noise(d,vol,freq,delay,type){
  if(muted)return;
  var a=ac();if(!a||!noiseBuf)return;
  try{
    var t0=a.currentTime+(delay||0);
    var s=a.createBufferSource(),f=a.createBiquadFilter(),g=a.createGain();
    s.buffer=noiseBuf;f.type=type||'highpass';f.frequency.value=freq||2000;
    g.gain.setValueAtTime(vol||0.05,t0);g.gain.exponentialRampToValueAtTime(0.0001,t0+d);
    s.connect(f);f.connect(g);g.connect(master);
    s.start(t0,Math.random()*0.5);s.stop(t0+d+0.02);
  }catch(e){}
}
var SFX={
  toss:function(){tone(520,.14,'square',.05,220);noise(.1,.03,3000);},
  hit:function(){tone(180,.3,'sawtooth',.08,50);tone(900,.08,'square',.04,300);noise(.25,.06,900);},
  pick:function(){tone(700,.07,'triangle',.07,1100);},
  coin:function(){tone(988,.06,'square',.04);tone(1319,.1,'square',.04,0,.06);},
  tag:function(){tone(330,.4,'sawtooth',.08,70);},
  canUp:function(){tone(150,.08,'square',.06,110);},
  pog:function(){[523,659,784,1046].forEach(function(f,i){tone(f,.09,'triangle',.06,0,i*.06);});},
  trap:function(){tone(300,.12,'sawtooth',.07,120);tone(220,.25,'square',.06,90,.1);},
  sync:function(){[659,784,988,1319].forEach(function(f,i){tone(f,.1,'square',.045,0,i*.05);});},
  warn:function(){tone(620,.18,'square',.05);tone(460,.18,'square',.05,0,.2);},
  strike:function(){tone(90,.5,'sawtooth',.12,40,0);tone(1200,.25,'square',.08,300,0);tone(60,.8,'triangle',.1,30,.05);},
  siren:function(){for(var i=0;i<3;i++){tone(520,.4,'sawtooth',.05,900,i*.8);tone(900,.4,'sawtooth',.05,520,i*.8+.4);}},
  thunder:function(){noise(.18,.12,5000);noise(1.3,.1,380,.05,'lowpass');tone(70,.9,'sine',.12,30,.04);},
  rumble:function(){noise(.7,.09,220,0,'lowpass');tone(48,.6,'sine',.1,30);},
  clear:function(){[523,659,784,1046].forEach(function(f,i){tone(f,.14,'square',.05,0,i*.1);});},
  over:function(){[392,330,262,196].forEach(function(f,i){tone(f,.2,'sawtooth',.06,0,i*.16);});},
  lock:function(){tone(880,.05,'square',.04);},
  click:function(){tone(660,.05,'triangle',.05);},
  buy:function(){[659,880,1175].forEach(function(f,i){tone(f,.1,'triangle',.06,0,i*.07);});},
  nope:function(){tone(200,.15,'square',.05,120);},
  start:function(){tone(330,.05,'square',.04);tone(494,.08,'square',.04,0,.06);}
};

/* ---------- dynamic BGM: 16-step sequencer, scheduled slightly ahead of the audio clock ---------- */
var BGM={step:0,next:0,mode:'',bpm:100};
var CHORDS=[45,41,48,43];                       // Am - F - C - G roots
var PENTA=[0,3,5,7,10,12,15];
var LEAD=[
  [0,-1,2,-1,3,-1,2,-1,4,-1,3,-1,2,-1,1,-1],    // street: bouncy marimba
  [0,2,4,2,3,1,2,-1,4,3,2,1,0,-1,1,-1],          // school: bright bell tune
  [0,2,4,2,0,2,4,5,4,2,0,2,4,5,6,5],             // city: neon arpeggio
  [0,-1,2,3,-1,2,0,-1,3,-1,4,3,-1,2,0,-1],        // beach: bouncy ukulele
  [0,-1,-1,2,-1,-1,4,-1,3,-1,-1,2,-1,-1,0,-1],    // forest: slow flute
  [0,-1,1,-1,0,-1,-1,5,-1,4,-1,-1,1,-1,0,-1]      // haunted: eerie minor
];
function mtof(m){return 440*Math.pow(2,(m-69)/12);}
function bgmMode(){
  var env=0,haz=null,chase=false,vol=1;
  if(scene==='PLAY'||scene==='PAUSE'||scene==='TIP'){
    env=G.cfg.env;haz=G.haz?G.haz.type:(scene==='TIP'?G.tip.type:null);chase=!!G.chase;
    if(scene==='PAUSE')vol=0.35;
  }else if(scene==='LEVEL_CLEAR'||scene==='LEVEL_FAILED')return null;
  else if(scene==='SELECT')env=Math.min(5,biome);
  else if(scene==='LOADING')return null;
  else vol=0.7;
  var bpm=[100,112,124,96,104,88][env]*(haz?1.22:1)*(chase?1.1:1);
  return {env:env,haz:haz,chase:chase,bpm:bpm,vol:vol,menu:scene!=='PLAY'&&scene!=='PAUSE'&&scene!=='TIP'};
}
function bgmStep(m,st,t){
  var a=AC,bar=Math.floor(st/16)%4,s=st%16,root=CHORDS[bar],v=m.vol*(m.menu?0.8:1);
  function N(f,d,type,vol,delay,dest){
    var o=a.createOscillator(),g=a.createGain();
    o.type=type;o.frequency.value=f;
    g.gain.setValueAtTime(vol*v,t);g.gain.exponentialRampToValueAtTime(0.0001,t+d);
    o.connect(g);g.connect(master);o.start(t);o.stop(t+d+0.02);
  }
  function NZ(d,vol,freq,type){
    var sr=a.createBufferSource(),f=a.createBiquadFilter(),g=a.createGain();
    sr.buffer=noiseBuf;f.type=type||'highpass';f.frequency.value=freq;
    g.gain.setValueAtTime(vol*v,t);g.gain.exponentialRampToValueAtTime(0.0001,t+d);
    sr.connect(f);f.connect(g);g.connect(master);sr.start(t,Math.random()*0.5);sr.stop(t+d+0.02);
  }
  var bassType=['triangle','sine','sawtooth','sine','triangle','sawtooth'][m.env],leadType=['square','triangle','sawtooth','triangle','sine','square'][m.env];
  // bass line (instrument changes with the biome)
  if(s%4===0)N(mtof(root-12),0.22,bassType,m.env===2?0.05:0.09);
  if(m.env===2&&s%4===2)N(mtof(root),0.1,'sawtooth',0.035);
  if(m.env===1&&s===10)N(mtof(root-5),0.2,'sine',0.07);
  // lead melody
  var li=LEAD[m.env][s];
  if(li>=0){
    var note=root+12+PENTA[li%PENTA.length]+(m.haz?0:0);
    N(mtof(note),m.env===1?0.34:0.16,leadType,m.env===2?0.03:0.045);
    if(m.env===1)N(mtof(note+12),0.2,'sine',0.02);
  }
  // percussion: only in the field, louder when chasing
  if(!m.menu){
    if(s%8===0)N(130,0.14,'sine',0.14);
    if(s%4===2)NZ(0.05,0.05,6000);
    if(m.chase){if(s%4===0)N(120,0.12,'sine',0.14);NZ(0.03,0.035,8000);if(s%8===4)NZ(0.12,0.07,1800);}
  }
  // disaster layer: dissonant drone, alarm beeps and a rumbling noise floor
  if(m.haz){
    if(s===0){N(mtof(root-24),0.9,'sawtooth',0.05);N(mtof(root-24+1),0.9,'sawtooth',0.03);}
    if(s===0||s===2||s===8||s===10)N(s%8===0?740:622,0.09,'square',0.035);
    if(m.haz==='STORM'&&s===6)NZ(0.25,0.06,4000);
    if((m.haz==='EARTHQUAKE'||m.haz==='LANDSLIDE')&&s%8===0)NZ(0.4,0.1,250,'lowpass');
    if((m.haz==='TYPHOON'||m.haz==='TORNADO')&&s%4===0)NZ(0.3,0.04,900,'bandpass');
    if(m.haz==='FIRE'&&s%2===1)NZ(0.04,0.04,5500);
    if(m.haz==='FLOOD'&&s%8===4)N(mtof(root+12),0.5,'sine',0.04);
  }
}
function bgmTick(){
  var a=ac&&AC;
  if(!a||muted||!musicOn||!noiseBuf){BGM.next=a?a.currentTime+0.05:0;return;}
  var m=bgmMode();
  if(!m){BGM.next=a.currentTime+0.05;return;}
  if(BGM.next<a.currentTime-0.2)BGM.next=a.currentTime+0.05;
  while(BGM.next<a.currentTime+0.25){
    m=bgmMode();if(!m)break;
    bgmStep(m,BGM.step,BGM.next);
    BGM.next+=60/m.bpm/4;BGM.step++;
  }
  BGM.bpm=m?m.bpm:BGM.bpm;
}
setInterval(function(){try{bgmTick();}catch(e){}},60);

""";

    private static final String JS_HAZARDS = """

/* =========================================================================
   PHILIPPINE DISASTERS (SDG 13): one every 15-25 s. Each one changes the field physics,
   drives the side-pillar animation and opens an SDG 13 safety-tip pop-up.
   ========================================================================= */
var HAZ_ORDER=['FLOOD','AMIHAN','EARTHQUAKE','LANDSLIDE','BROWNOUT','ASHFALL'];
/* one NEW disaster per biome; earlier biomes' disasters keep coming in later biomes */
var DIS_BY_BIOME=[['FLOOD'],['AMIHAN'],['EARTHQUAKE','LANDSLIDE'],['BROWNOUT'],['ASHFALL']];
function hazBiome(t){for(var b=0;b<DIS_BY_BIOME.length;b++)if(DIS_BY_BIOME[b].indexOf(t)>=0)return b;return 0;}
function availHaz(env){var o=[];for(var b=0;b<=Math.min(NB-1,env);b++)o=o.concat(DIS_BY_BIOME[b]);return o;}
var HAZ={
  AMIHAN:{label:'AMIHAN GUSTS',title:'AMIHAN GUSTS & HIGH TIDE!',col:'#4fd0ff',agency:'PAGASA gale and storm-surge warnings',
    desc:'Heavy sea winds curve every throw mid-air while rising waves swallow parts of the safe zone.',
    hazard:'Wind bends slippers sideways. High-tide waves slow anyone they cover by 55%.',season:'Amihan (northeast monsoon), October to March'},
  BROWNOUT:{label:'BROWNOUT',title:'BROWNOUT!',col:'#ffd23f',agency:'Meralco / NGCP grid advisories',
    desc:'The power goes out! The court turns pitch black except for small circles of light around players and sparks from the lata.',
    hazard:'You can only see the Taya when she is inside a light circle. Listen and watch her patrol.',season:'Hot summer afternoons and after strong storms'},
  ASHFALL:{label:'ASHFALL & LAVA',title:'ASHFALL & LAVA GEYSERS!',col:'#ff7a2a',agency:'PHIVOLCS volcano bulletins',
    desc:'Ash rains down and slowly shrinks your view while lava vents erupt and launch anyone standing on them.',
    hazard:'Visibility shrinks over time. Glowing vents erupt after a warning: step off them or be flung into the air!',season:'Mayon Volcano - Alert Levels 1 to 5'},
  TYPHOON:{label:'TYPHOON',title:'TYPHOON!',col:'#6aa7ff',agency:'PAGASA wind signals',
    desc:'Strong crosswinds sweep the court and push both slippers and players sideways.',
    hazard:'Gale-force wind + driving rain. Heavier Pamato upgrades resist the wind.',season:'June - November, up to 20 a year'},
  FLOOD:{label:'BIGLAANG BUHOS',title:'BIGLAANG BUHOS! FLASH FLOOD!',col:'#2fd0d8',agency:'PAGASA rainfall warnings',
    desc:'A sudden tropical downpour floods the street. Currents drag stray tsinelas away and everyone wades slowly.',
    hazard:'Movement -45% for hitters AND the Taya. Floodwater currents push tsinelas on the ground sideways.',season:'Monsoon (habagat) and typhoon rains'},
  EARTHQUAKE:{label:'EARTHQUAKE',title:'EARTHQUAKE!',col:'#d9a066',agency:'PHIVOLCS',
    desc:'The ground shakes! Players are shoved around and the power meter jitters.',
    hazard:'Random shoves every ~1.5 s, faster meter, camera shake.',season:'Ring of Fire - anytime, no warning'},
  LANDSLIDE:{label:'LANDSLIDE',title:'LANDSLIDE!',col:'#b0743c',agency:'MGB geohazard maps',
    desc:'Boulders tumble onto the court and become temporary blockers that stop slippers.',
    hazard:'Up to 5 rocks drop in; each blocks movement and throws for 9 seconds.',season:'After days of heavy rain in the mountains'},
  FIRE:{label:'FIRE',title:'FIRE!',col:'#ff6a3a',agency:'Bureau of Fire Protection (911)',
    desc:'Fire patches flare up on the court. Run through one and you are slowed to a crawl.',
    hazard:'Fire zones cut speed by 65% for anyone inside - Taya included.',season:'Dry months (March - May) are fire season'},
  TORNADO:{label:'TORNADO',title:'TORNADO!',col:'#c4b5fd',agency:'PAGASA',
    desc:'A roaming twister drags players toward it and bends every slipper in flight.',
    hazard:'A moving vortex pulls anything within ~170 px. Throw around it!',season:'Rare, but real - from severe thunderclouds'},
  STORM:{label:'SEVERE STORM',title:'THUNDERSTORM!',col:'#ffe066',agency:'PAGASA thunderstorm advisory',
    desc:'Lightning strikes the court! A glowing circle warns you one second before it hits.',
    hazard:'Anyone caught in a strike (Taya too) is stunned for ~1.5 s. Wind gusts.',season:'Hot afternoons, especially April - October'}
};
function tipFor(type){
  var list=TIPS[type]||['Stay safe!'];
  var i=(tipIdx[type]||0)%list.length;tipIdx[type]=(tipIdx[type]||0)+1;
  return {text:list[i],n:i+1,of:list.length};
}
function triggerHazard(type){
  if(G.haz||scene!=='PLAY')return;
  var av=availHaz(G.cfg.env);
  if(!type||av.indexOf(type)<0){do{type=pick(av);}while(type===G.lastHaz&&av.length>1);}
  G.lastHaz=type;
  G.hazTimer=rnd(G.cfg.hazMin,G.cfg.hazMax);
  startHazard(type);                  // no pop-up, no pause: the disaster simply hits mid-game
  shake=Math.max(shake,10);G.flash=0.3;
  SFX.strike();
  bus.emit('hazardWarn',type);
}
function ackTip(){}
function ackBrief(){if(scene!=='BRIEF')return;G.brief=null;scene='PLAY';SFX.start();tutBegin();}
function startHazard(type){
  var dir=Math.random()<.5?-1:1;
  G.haz={type:type,t:10,dur:10,wx:0,wy:0,q:0.6,rt:0.4,st:1.2,vx:0,vy:0,vdir:dir};
  G.hazCount++;
  if(type==='AMIHAN'){G.haz.wx=dir*rnd(30,42)*K;G.haz.wy=rnd(-6,6)*K;G.haz.tide=0.8;}
  else if(type==='ASHFALL'){
    for(var v=0;v<3;v++){
      for(var vt=0;vt<30;vt++){
        var vx=rnd(110,AW-110),vy=rnd(320,G.line-90);
        if(!inObstacle(vx,vy,34)&&Math.hypot(vx-CAN_X,vy-CAN_Y)>110){G.zones.push({type:'vent',x:vx,y:vy,r:38,t:G.haz.dur+1,cd:rnd(1.2,3.2),st:0,w:0});break;}
      }
    }
  }
  else if(type==='TYPHOON'){G.haz.wx=dir*rnd(26,38)*K;G.haz.wy=rnd(-8,8)*K;}
  else if(type==='TORNADO'){G.haz.vx=dir>0?60:AW-60;G.haz.vy=rnd(280,420);}
  else if(type==='FIRE'){
    for(var i=0;i<4;i++){
      for(var t=0;t<30;t++){
        var x=rnd(70,AW-70),y=rnd(260,G.line-70);
        if(!inObstacle(x,y,30)){G.zones.push({type:'fire',x:x,y:y,r:48,t:10,ph:Math.random()*6});break;}
      }
    }
  }
  toast(HAZ[type].title,HAZ[type].col);
  bus.emit('hazardStart',type);
}
function hazMul(e){
  var m=1,i;
  if(G.haz&&G.haz.type==='FLOOD')m*=0.55;
  var rs=e.char==='BERTO'?sl(e,'BERTO',3):0;if(rs&&m<1)m=Math.min(1,m+(1-m)*[0,0.5,0.8,1][rs]);   // Boyet: disaster resistance
  if(inSmoke(e)&&m<1)m=1;                                    // Boyet's smoke shields teammates
  for(i=0;i<G.zones.length;i++){var z=G.zones[i];if(z.type==='fire'&&Math.hypot(e.x-z.x,e.y-z.y)<z.r)m*=0.35;else if(z.type==='tide'&&Math.hypot((e.x-z.x)*0.6,e.y-z.y)<z.r)m*=0.45;}
  return m;
}
/* sideways forces on walking entities: typhoon wind + tornado suction */
function hazPush(e,dt,k){
  if(e.char){var rw=e.char==='BERTO'?sl(e,'BERTO',3):0;if(rw>=2)k*=rw>=3?0.5:0.75;if(inSmoke(e))k*=0.25;}
  if(e.air>0){e.air-=dt;e.x=clamp(e.x+e.lvx*dt,24,AW-24);e.y=clamp(e.y+e.lvy*dt,TOP+14,e.maxY||BOT);}
  if(G.twx||G.twy){e.x+=G.twx*k*dt;e.y+=G.twy*k*dt;}
  var h=G.haz;
  if(h&&h.type==='TORNADO'){
    var dx=h.vx-e.x,dy=h.vy-e.y,d=Math.hypot(dx,dy);
    if(d<170&&d>1){var f=(1-d/170)*150*dt;e.x+=dx/d*f-dy/d*f*0.5;e.y+=dy/d*f+dx/d*f*0.5;}
  }
}
function hazFlight(s,dt){
  var h=G.haz;
  if(h&&h.type==='TORNADO'){
    var dx=h.vx-s.x,dy=h.vy-s.y,d=Math.hypot(dx,dy);
    if(d<210&&d>1){var f=(1-d/210)*340*dt;s.dx+=dx/d*f-dy/d*f*0.8;s.dy+=dy/d*f+dx/d*f*0.8;}
  }
}
function strikeAt(x,y){
  G.flash=0.3;G.bolt={x:x,y:y,t:0.25};shake=14;SFX.thunder();
  burst(x,y,'#fff7a0',26,140);
  for(var i=0;i<G.hitters.length;i++){
    var h=G.hitters[i];
    if(Math.hypot(h.x-x,h.y-y)<56){h.stun=1.5;h.meter.on=false;pop(h.x,h.y-90,'ZAPPED!','#fff36b');}
  }
  var T=G.taya;
  if(Math.hypot(T.x-x,T.y-y)<62){T.state='STUN';T.stun=1.6;T.zap=1.6;T.laugh=0;pop(T.x,T.y-110,'ZAPPED!','#fff36b');}
}
function ventBlast(z){
  shake=Math.max(shake,8);SFX.rumble&&SFX.rumble();burst(z.x,z.y,'#ff7a2a',22,150);
  var all=G.hitters.concat([G.taya]),i;
  for(i=0;i<all.length;i++){
    var e=all[i];
    if(Math.hypot((e.x-z.x),(e.y-z.y)*1.6)<z.r*1.25){
      var a=Math.random()*6.2832;e.air=0.7;e.lvx=Math.cos(a)*230;e.lvy=Math.sin(a)*170;
      if(e.meter){e.stun=Math.max(e.stun||0,0.7);e.meter.on=false;pop(e.x,e.y-90,'WHOOSH!','#ffb02e');}
      else{e.state='STUN';e.stun=0.9;pop(e.x,e.y-110,'WHOOSH!','#ffb02e');}
    }
  }
  for(i=0;i<G.slippers.length;i++){var s=G.slippers[i];if(s.state!=='fly'&&Math.hypot(s.x-z.x,(s.y-z.y)*1.6)<z.r*1.3){var a2=Math.random()*6.2832,d=rnd(90,170);s.x=clamp(s.x+Math.cos(a2)*d,30,AW-30);s.y=clamp(s.y+Math.sin(a2)*d*.7,TOP+30,G.line+40);}}
}
function updateHazard(dt){
  var A=G.cfg.gust*K,i;
  G.gx=A*Math.sin(G.time*0.55+G.gustPh);G.gy=A*0.35*Math.sin(G.time*0.9+1.3);
  G.twx=0;G.twy=0;
  if(G.flash>0)G.flash-=dt;
  if(G.bolt)G.bolt.t-=dt;
  // zones: fire patches, falling boulders and lightning warnings keep ticking
  for(i=0;i<G.zones.length;i++){
    var z=G.zones[i];z.t-=dt;
    if(z.type==='rockfall'&&z.t<=0){
      z.dead=true;
      G.obst.push({type:'rock',dyn:false,block:true,w:54,h:46,vx:0,x:z.x,y:z.y,ttl:9});
      shake=9;SFX.rumble();burst(z.x,z.y,'#a07a4a',14,90);
    }else if((z.type==='fire'||z.type==='tide')&&z.t<=0)z.dead=true;
    else if(z.type==='vent'){
      z.cd-=dt;
      if(z.st===0&&z.cd<=0){z.st=1;z.cd=1.1;}
      else if(z.st===1&&z.cd<=0){z.st=2;z.cd=0.7;ventBlast(z);}
      else if(z.st===2&&z.cd<=0){z.st=0;z.cd=rnd(2.4,4);}
      if(z.st===2&&Math.random()<dt*40)G.parts.push({x:z.x+rnd(-12,12),y:z.y,vx:rnd(-30,30),vy:rnd(-300,-160),t:0,life:.7,col:pick(['#ff5a2a','#ffb02e','#fff07a']),size:rnd(5,10),g:420});
    }
    if(z.type==='fire'&&false){}
    else if(z.type==='fire'&&Math.random()<dt*14)G.parts.push({x:z.x+rnd(-z.r*.6,z.r*.6),y:z.y+rnd(-10,10),vx:rnd(-10,10),vy:rnd(-90,-40),t:0,life:.6,col:pick(['#ff5a2a','#ffb02e','#fff07a']),size:rnd(4,9),g:-30});
  }
  G.zones=G.zones.filter(function(z){return !z.dead;});
  for(i=0;i<G.strikes.length;i++){
    var s=G.strikes[i];s.t-=dt;
    if(s.t<=0){s.dead=true;strikeAt(s.x,s.y);}
  }
  G.strikes=G.strikes.filter(function(s){return !s.dead;});
  var h=G.haz;
  if(h){
    h.t-=dt;
    if(h.type==='FLOOD'){for(i=0;i<G.slippers.length;i++){var fs=G.slippers[i];if(fs.state!=='fly'&&!fs.carried){fs.x=clamp(fs.x+h.vdir*46*dt,30,AW-30);}}}
    var fade=clamp(Math.min(h.dur-h.t,h.t)/1.2,0,1);
    if(h.type==='TYPHOON'||h.type==='AMIHAN'){var g=0.75+0.25*Math.sin(G.time*1.7);G.twx=h.wx*g*fade;G.twy=h.wy*g*fade;}
    else if(h.type==='ASHFALL'){h.ash=1-h.t/h.dur;}
    else if(h.type==='BROWNOUT'){h.sp=(h.sp||0)-dt;}
    else if(h.type==='STORM'){G.twx=h.wx||0;G.twx=Math.sin(G.time*1.3)*20*K*fade;}
    else if(h.type==='EARTHQUAKE'){
      shake=Math.max(shake,3.5);h.q-=dt;
      if(h.q<=0){
        h.q=rnd(1.2,1.8);shake=11;SFX.rumble();
        var all=G.hitters.concat([G.taya]);
        for(i=0;i<all.length;i++){var e=all[i];e.x+=rnd(-46,46);e.y+=rnd(-30,30);pushOut(e,14);e.x=clamp(e.x,24,AW-24);e.y=clamp(e.y,TOP+14,e.maxY||BOT);if(e.meter)e.meter.p+=rnd(-.6,.6);}
      }
    }else if(h.type==='LANDSLIDE'){
      h.rt-=dt;
      if(h.rt<=0&&h.t>3){
        h.rt=rnd(1.2,1.8);
        var rocks=0;for(i=0;i<G.obst.length;i++)if(G.obst[i].type==='rock')rocks++;
        if(rocks+G.zones.length<5)G.zones.push({type:'rockfall',x:rnd(70,AW-70),y:rnd(280,G.line-80),t:1.0,t0:1.0});
      }
    }else if(h.type==='TORNADO'){
      var u=1-h.t/h.dur;
      h.vx=AW/2+Math.sin(u*Math.PI*2*1.1+(h.vdir>0?0:Math.PI))*(AW/2-90);
      h.vy=360+Math.sin(u*Math.PI*3)*130;
    }else if(h.type==='STORM'){
      h.st-=dt;
      if(h.st<=0&&h.t>2){
        h.st=rnd(1.0,1.6);
        var tg=Math.random()<.5&&G.hitters.length?pick(G.hitters):null;
        var sx=tg&&tg.y<G.line+60?tg.x+rnd(-30,30):rnd(70,AW-70),sy=tg&&tg.y<G.line+60?tg.y+rnd(-20,20):rnd(260,G.line-60);
        G.strikes.push({x:clamp(sx,40,AW-40),y:clamp(sy,TOP+30,BOT-20),t:1.0,t0:1.0});
      }
    }
    if(h.type==='AMIHAN'){
      h.tide-=dt;
      var tn=0;for(i=0;i<G.zones.length;i++)if(G.zones[i].type==='tide')tn++;
      if(h.tide<=0&&h.t>3&&tn<2){h.tide=rnd(1.8,3);G.zones.push({type:'tide',x:rnd(110,AW-110),y:G.line+rnd(10,50),r:96,t:5,t0:5,ph:Math.random()*6});}
    }
    if(h.type==='BROWNOUT'&&h.sp<=0){h.sp=0.12;G.parts.push({x:G.can.x+rnd(-24,24),y:G.can.y-rnd(10,50),vx:rnd(-50,50),vy:rnd(-90,-30),t:0,life:.5,col:'#ffe27a',size:rnd(3,6),g:60});}
    if(h.t<=0){G.haz=null;G.zones=G.zones.filter(function(z){return z.type==='rockfall';});G.tans=G.tans||[];G.strikes=[];toast('The weather is clearing up','#c8f0c8');bus.emit('hazardEnd');}
  }
  G.wx=G.gx+G.twx;G.wy=G.gy+G.twy;
  if(!G.haz){
    G.hazTimer-=dt;
    if(G.hazTimer<=0)triggerHazard();
  }
}

""";

    private static final String JS_SCREENS = """
/* =========================================================================
   SCREENS - main menu, level select map, shop, pause, results
   ========================================================================= */
function dim(a){present();ctx.fillStyle='rgba(10,14,24,'+a+')';ctx.fillRect(0,0,W,H);}
function panel(x,y,w,h,col){
  RR(x+0,y+8,w,h,26,'rgba(0,0,0,.4)',0);
  RR(x,y,w,h,26,col||'#363c52',5);
  RR(x+10,y+10,w-20,18,9,'rgba(255,255,255,.10)',0);
}
/* ---------- level-complete celebration: pixel confetti, bouncing panel, star pops, rolling coin counter ---------- */
var CONF=[];
function celebrateInit(){
  G.cAnim=0;G.cStars=0;CONF=[];
  var cols=['#ffd23f','#d8452e','#2a62c9','#3cb371','#ffffff','#e8803a'];
  for(var i=0;i<110;i++)CONF.push({x:rnd(0,W),y:rnd(-H,0),vx:rnd(-40,40),vy:rnd(120,300),s:rnd(6,14),col:cols[i%6],ph:rnd(0,6)});
}
function celebrateUpdate(dt){
  if(!G)return;
  G.cAnim+=dt;
  for(var i=0;i<CONF.length;i++){var c=CONF[i];c.x+=Math.sin(G.cAnim*3+c.ph)*40*dt+c.vx*dt;c.y+=c.vy*dt;if(c.y>H+20){c.y=-20;c.x=rnd(0,W);}}
  var st=G.result?G.result.stars:0,due=Math.min(st,Math.max(0,Math.floor((G.cAnim-0.6)/0.4)+1));
  while(G.cStars<due){G.cStars++;SFX.pick();}
}
function drawConfetti(){for(var i=0;i<CONF.length;i++){var c=CONF[i];ctx.fillStyle=c.col;ctx.fillRect(c.x,c.y,c.s,c.s*(0.5+0.5*Math.abs(Math.sin(G.cAnim*5+c.ph))));}}
function easeBack(t){t=clamp(t,0,1);var c1=1.9,c3=c1+1;return 1+c3*Math.pow(t-1,3)+c1*Math.pow(t-1,2);}
function starPop(i,x,y,size,on){
  var t=(G.cAnim-0.6-i*0.4)/0.45;
  if(t<=0){drawStar(x,y,size,false);return;}
  var k=on?(1+(1-easeBack(t))*1.6):1;
  ctx.save();ctx.translate(x,y);ctx.scale(k,k);ctx.rotate(on?(1-clamp(t,0,1))*0.8:0);drawStar(0,0,size,on);ctx.restore();
  if(on&&t>0&&t<1){ctx.strokeStyle='rgba(255,240,160,'+(1-t)+')';ctx.lineWidth=6;ctx.beginPath();ctx.arc(x,y,size*(1+t*1.6),0,6.2832);ctx.stroke();}
}
/* ---------- pixel icons for the home-page icon buttons ---------- */
function drawIcon(id,cx,cy,col){
  var c=col||'#faf3e0',d='#10131c',o='#f5c542',i;
  function f(x,y,w,h,k){ctx.fillStyle=k;ctx.fillRect(cx+x,cy+y,w,h);}
  if(id==='shop'){          // tsinelas (flip-flop)
    ctx.fillStyle=d;ctx.beginPath();ctx.ellipse(cx,cy+2,13,21,0.15,0,6.2832);ctx.fill();
    ctx.fillStyle='#43b3a5';ctx.beginPath();ctx.ellipse(cx,cy+2,10.5,18.5,0.15,0,6.2832);ctx.fill();
    f(-2,-12,4,12,d);f(-12,-2,8,4,d);f(4,-2,8,4,d);f(-1,-12,2,10,o);f(-10,-1,6,2,o);f(4,-1,6,2,o);
  }else if(id==='rules'){   // rule book
    f(-16,-20,32,40,d);f(-14,-18,28,36,'#f4ecd6');f(-14,-18,5,36,'#d9742a');
    for(i=0;i<4;i++)f(-4,-12+i*8,14,2,'#5a6480');
  }else if(id==='pog'){     // pog disc with star
    ctx.fillStyle=d;ctx.beginPath();ctx.arc(cx,cy,21,0,6.2832);ctx.fill();
    ctx.fillStyle='#e8433a';ctx.beginPath();ctx.arc(cx,cy,18,0,6.2832);ctx.fill();
    ctx.fillStyle='#ffd23f';ctx.beginPath();ctx.arc(cx,cy,11,0,6.2832);ctx.fill();
    drawStar(cx,cy,8,true);
  }else if(id==='disaster'){// cloud + lightning
    ctx.fillStyle=d;ctx.beginPath();ctx.arc(cx-8,cy-6,12,0,6.2832);ctx.arc(cx+8,cy-8,14,0,6.2832);ctx.arc(cx+16,cy,9,0,6.2832);ctx.fill();ctx.fillRect(cx-20,cy-4,40,10);
    ctx.fillStyle='#bcc6dc';ctx.beginPath();ctx.arc(cx-8,cy-6,10,0,6.2832);ctx.arc(cx+8,cy-8,12,0,6.2832);ctx.arc(cx+16,cy,7,0,6.2832);ctx.fill();ctx.fillRect(cx-18,cy-4,36,8);
    ctx.fillStyle=d;ctx.beginPath();ctx.moveTo(cx+4,cy+2);ctx.lineTo(cx-8,cy+16);ctx.lineTo(cx-1,cy+16);ctx.lineTo(cx-6,cy+28);ctx.lineTo(cx+10,cy+12);ctx.lineTo(cx+3,cy+12);ctx.lineTo(cx+9,cy+2);ctx.closePath();ctx.fill();
    ctx.fillStyle='#ffd23f';ctx.beginPath();ctx.moveTo(cx+4,cy+4);ctx.lineTo(cx-6,cy+15);ctx.lineTo(cx+1,cy+15);ctx.lineTo(cx-4,cy+24);ctx.lineTo(cx+8,cy+11);ctx.lineTo(cx+1,cy+11);ctx.lineTo(cx+6,cy+4);ctx.closePath();ctx.fill();
  }else if(id==='profile'){ // person bust
    ctx.fillStyle=d;ctx.beginPath();ctx.arc(cx,cy-9,14,0,6.2832);ctx.fill();ctx.fillRect(cx-21,cy+8,42,20);
    ctx.fillStyle='#f2c08a';ctx.beginPath();ctx.arc(cx,cy-9,11.5,0,6.2832);ctx.fill();
    ctx.fillStyle='#43b3a5';ctx.fillRect(cx-18,cy+11,36,15);f(-12,-20,24,7,'#2a2236');
  }else if(id==='chars'){   // two heroes
    for(i=0;i<2;i++){var ox=i?10:-10,oy=i?4:-2;
      ctx.fillStyle=d;ctx.beginPath();ctx.arc(cx+ox,cy+oy-8,11,0,6.2832);ctx.fill();ctx.fillRect(cx+ox-14,cy+oy+6,28,16);
      ctx.fillStyle=i?'#f2c08a':'#e8a878';ctx.beginPath();ctx.arc(cx+ox,cy+oy-8,9,0,6.2832);ctx.fill();
      ctx.fillStyle=i?'#e8873a':'#38b6ff';ctx.fillRect(cx+ox-12,cy+oy+8,24,12);f(ox-9,oy-17,18,5,i?'#2a2236':'#3a2a1a');}
  }else if(id==='trophy'){
    f(-14,-20,28,20,d);f(-12,-18,24,17,o);f(-22,-16,9,4,d);f(-22,-16,9,12,d);f(-20,-14,5,8,'#f5c542');f(13,-16,9,12,d);f(15,-14,5,8,'#f5c542');
    f(-6,0,12,10,d);f(-4,0,8,9,o);f(-12,10,24,8,d);f(-10,11,20,6,'#d9742a');f(-6,-14,4,8,'#fff3b0');
  }else if(id==='music'||id==='musicoff'){
    ctx.fillStyle=d;ctx.beginPath();ctx.ellipse(cx-8,cy+14,10,8,-0.3,0,6.2832);ctx.fill();ctx.fillRect(cx-1,cy-20,5,34);ctx.fillRect(cx+2,cy-22,16,8);
    ctx.fillStyle=id==='music'?'#43e07a':'#8a94aa';ctx.beginPath();ctx.ellipse(cx-8,cy+14,8,6,-0.3,0,6.2832);ctx.fill();ctx.fillRect(cx,cy-18,3,31);ctx.fillRect(cx+3,cy-20,13,5);
    if(id==='musicoff'){ctx.strokeStyle='#e8433a';ctx.lineWidth=5;ctx.beginPath();ctx.moveTo(cx-20,cy-20);ctx.lineTo(cx+20,cy+22);ctx.stroke();}
  }
}
function drawBtn(b,hot){
  if(b.draw){b.draw(b,hot);return;}
  if(b.icon){
    var up=hot?3:0;
    RR(b.x,b.y+6,b.w,b.h,16,'rgba(0,0,0,.45)',0);
    RR(b.x,b.y-up,b.w,b.h,16,hot?'#e8873a':'#363c56',4);
    RR(b.x+6,b.y+5-up,b.w-12,b.h*.28,8,'rgba(255,255,255,.14)',0);
    drawIcon(b.icon,b.x+b.w/2,b.y+b.h/2-up);
    if(hot){var tw=b.label.length*9+22;RR(b.x+b.w/2-tw/2,b.y+b.h+12,tw,28,8,'#10131c',3);txt(b.label,b.x+b.w/2,b.y+b.h+26,18,'#faf3e0','center',false);}
    return;
  }
  var base=b.on?'#2fcf6b':(hot?'#ffd23f':'#d9742a');
  RR(b.x,b.y+7,b.w,b.h,18,'rgba(0,0,0,.45)',0);
  RR(b.x,b.y-(hot?3:0),b.w,b.h,18,base,4);
  RR(b.x+8,b.y+6-(hot?3:0),b.w-16,b.h*.3,10,'rgba(255,255,255,.28)',0);
  txt(b.label,b.x+b.w/2,b.y+b.h/2-(hot?3:0),b.h*.42,hot&&!b.on?'#1d1b2e':'#fff','center',!(hot&&!b.on));
}
function drawButtons(){
  var bs=buttonsFor(scene);
  for(var i=0;i<bs.length;i++){
    var hot=(scene==='MENU')?inRect(mouse,bs[i]):(i===sel);
    drawBtn(bs[i],hot);
  }
}
var selBG=[];
function bgFor(env){if(!selBG[env])selBG[env]=buildPixBG(env,590,W);return selBG[env];}
function coinPill(x,y){
  RR(x-170,y-24,170,48,24,'#2b3042',3);coinIcon(x-140,y,15);
  txt(String(SAVE.coins),x-14,y,26,'#ffe27a','right');
}
function lockIcon(x,y,s){
  ctx.save();ctx.translate(x,y);ctx.scale(s,s);
  ctx.strokeStyle=INK;ctx.lineWidth=6;ctx.beginPath();ctx.arc(0,-6,9,Math.PI,0);ctx.stroke();
  ctx.strokeStyle='#c4cad8';ctx.lineWidth=3;ctx.stroke();
  RR(-13,-6,26,22,5,'#c4cad8',3);circ(0,5,3,INK,0);ctx.fillStyle=INK;ctx.fill();
  ctx.restore();
}

/* ---------- level select: biome slider with clickable level boxes ---------- */
function drawSelect(){
  ctx.drawImage(bgFor(clamp(biome,0,NB-1)),0,0);dim(.6);
  txt('SELECT LEVEL',60,48,44,'#ffd23f','left');txt(SAVE.name+'  -  '+(players===2?'CO-OP SAVE':'SOLO SAVE'),420,48,22,'#ffcf8a','left');
  coinPill(W-40,46);
  drawStar(W-250,46,15,true);txt(String(totalStars()),W-228,46,24,'#ffe66a','left');
  var bs=buttonsFor('SELECT'),i;
  for(var b=0;b<=NB;b++){
    var ox=b*W-slideX;
    if(Math.abs(ox)>W)continue;
    if(b===NB){
      panel(W/2+ox-330,200,660,330,'rgba(30,35,52,.92)');
      txt('COMING SOON',W/2+ox,276,64,'#ffd23f','center');
      txt('More biomes are being built by the barangay...',W/2+ox,330,22,'#f4ecd6','center',false);
      for(i=0;i<3;i++){RR(W/2+ox-250+i*170,370,150,110,18,'#3a3470',4);lockIcon(W/2+ox-175+i*170,425,2);txt('???',W/2+ox-175+i*170,468,18,'#9aa0d8','center',false);}
      continue;
    }
    var open=biomeOpen(b);
    txt(BIOMES[b].name+' BIOME   -   '+BIOMES[b].range,W/2+ox,196,30,BIOMES[b].col,'center');
    var stars=biomeStars(SAVE,b);
    if(open)txt('Stars  '+stars+' / 30',W/2+ox,226,18,'#ffe66a','center',false);
    else txt('LOCKED  -  earn '+GATE[b]+' stars from earlier biomes  ('+starsBefore(b)+' / '+GATE[b]+')',W/2+ox,226,20,'#ff9a8a','center');
    for(i=1;i<=10;i++){
      var n=b*10+i,r=boxRect(n),un=unlocked(n),isSel=(n===selLevel);
      var lift=isSel?-6+Math.sin(anim*5)*2:0;
      RR(r.x,r.y+lift+8,r.w,r.h,20,'rgba(0,0,0,.4)',0);
      var gr=ctx.createLinearGradient(0,r.y,0,r.y+r.h);
      if(un){gr.addColorStop(0,BIOMES[b].col);gr.addColorStop(1,BIOMES[b].col2);}else{gr.addColorStop(0,'#6b6791');gr.addColorStop(1,'#46426b');}
      RR(r.x,r.y+lift,r.w,r.h,20,gr,isSel?6:4);
      RR(r.x+8,r.y+lift+7,r.w-16,r.h*.22,10,'rgba(255,255,255,.22)',0);
      if(isSel){ctx.strokeStyle='#ffd23f';ctx.lineWidth=6;rrect(r.x-5,r.y+lift-5,r.w+10,r.h+10,24);ctx.stroke();}
      if(un){
        txt(String(n),r.x+r.w/2,r.y+lift+52,60,'#fff','center');
        for(var s=0;s<3;s++)drawStar(r.x+r.w/2-44+s*44,r.y+lift+112,17,SAVE.stars[n-1]>s);
      }else{
        lockIcon(r.x+r.w/2,r.y+lift+66,2.2);
        txt(String(n),r.x+r.w/2,r.y+lift+124,24,'#c4cad8','center');
      }
    }
  }
  for(i=0;i<bs.length;i++){
    var bt=bs[i],hot=(i===sel);
    if(bt.tab!==undefined){
      var on=biome===bt.tab,lk=bt.tab<NB&&!biomeOpen(bt.tab);
      RR(bt.x,bt.y+(on?0:6),bt.w,bt.h-(on?0:6),14,on?BIOMES[bt.tab].col:'#3a3470',4);
      txt(bt.label,bt.x+bt.w/2+(lk?8:0),bt.y+bt.h/2+(on?0:3),16,on?'#fff':'#b9b4e8','center');
      if(lk)lockIcon(bt.x+16,bt.y+bt.h/2+3,.55);
    }else if(bt.label==='<'||bt.label==='>'){
      var left=bt.label==='<',dis=left?biome<=0:biome>=NB;
      ctx.globalAlpha=dis?.35:1;
      RR(bt.x,bt.y,bt.w,bt.h,22,hot&&!dis?'#ffd23f':'#d9742a',4);
      ctx.strokeStyle=hot&&!dis?INK:'#fff';ctx.lineWidth=10;ctx.beginPath();
      var cx=bt.x+bt.w/2,cy=bt.y+bt.h/2,d=left?-1:1;
      ctx.moveTo(cx-d*10,cy-24);ctx.lineTo(cx+d*12,cy);ctx.lineTo(cx-d*10,cy+24);ctx.stroke();
      ctx.globalAlpha=1;
    }else if(!bt.lv)drawBtn(bt,hot);
  }
  txt((players===1?'SOLO MODE (3 pamato max)':'2-PLAYER CO-OP'),W/2,664,22,'#ffffff','center');
  txt('Arrow keys: choose   |   ENTER: play   |   PgUp/PgDn: biome   |   S: shop   |   ESC: back',W/2,704,15,'#e8e0cc','center',false);
  if(selMsgT>0){ctx.globalAlpha=Math.min(1,selMsgT*2);RR(W/2-340,585,680,40,14,'#3a0f2a',3);txt(selMsg,W/2,606,19,'#ffb0a0','center',false);ctx.globalAlpha=1;}
}

/* ---------- Tsinelas shop ---------- */
function trailDemo(id,cx,cy){
  var sk=SKINS[id],tr=sk.trail;
  if(!tr)return;
  for(var i=0;i<9;i++){
    var t=(anim*1.4+i*0.11)%1,x=cx-120+t*80+Math.sin(i*2.3)*5,y=cy+34-t*20+Math.cos(i*1.7+anim*3)*8;
    ctx.globalAlpha=1-t;
    ctx.fillStyle=tr==='purple'?(i%2?'#b98cff':'#8b4dff'):tr==='petal'?(i%2?'#ff9ec7':'#ffffff'):tr==='glyph'?(i%2?'#6a3a14':'#a8742f'):tr==='fire'?(i%2?'#ff5a1f':'#ffd23f'):tr==='jeep'?['#e0262e','#ffd23f','#2a62c9','#fff'][i%4]:(i%2?'#ffd23f':'#fff3a0');
    var sz=tr==='petal'?9:7;ctx.fillRect(x,y,sz,sz);
  }
  ctx.globalAlpha=1;
}
function drawShop(){
  ctx.drawImage(bgFor(0),0,0);dim(.68);
  txt('TSINELAS SHOP',W/2,50,52,'#ffd23f','center');txt((players===2?'CO-OP SAVE':'SOLO SAVE')+' - '+SAVE.name,W/2,88,16,'#ffcf8a','center',false);
  coinPill(W-40,46);
  txt(shopTab?'TANSAN SKINS':'PAMATO SKINS',50,152,28,'#ff9ed0','left');
  RR(W-210,80,170,40,20,'#17394a',3);tansanIcon(W-184,100,13);txt(String(SAVE.tansan||0),W-58,100,22,'#9ff0ff','right');
  txt('UPGRADES',660,152,28,'#f5c542','left');
  if(shopMsgT>0){ctx.globalAlpha=Math.min(1,shopMsgT*2);txt(shopMsg,W/2,112,22,'#fff3a0','center');ctx.globalAlpha=1;}
  var bs=buttonsFor('SHOP'),i;
  for(i=0;i<bs.length;i++){
    var b=bs[i],hot=(i===sel)||inRect(mouse,b);
    if(b.kind==='skin'){
      var sk=SKINS[b.key],owned=SAVE.owned.indexOf(b.key)>=0,eq=SAVE.skin===b.key;
      RR(b.x,b.y+7,b.w,b.h,20,'rgba(0,0,0,.4)',0);
      RR(b.x,b.y,b.w,b.h,20,eq?'#2c6b4f':'#363c52',hot?6:4);
      if(hot){ctx.strokeStyle='#ffd23f';ctx.lineWidth=5;rrect(b.x-4,b.y-4,b.w+8,b.h+8,23);ctx.stroke();}
      ctx.fillStyle='rgba(255,255,255,.1)';ctx.beginPath();ctx.ellipse(b.x+b.w/2,b.y+70,70,42,0,0,6.3);ctx.fill();
      trailDemo(b.key,b.x+b.w/2+48,b.y+62);
      ctx.save();ctx.translate(b.x+b.w/2+20,b.y+68+Math.sin(anim*3+i)*4);slipperShape(0.9+Math.sin(anim*2)*.1,false,3.3,sk);ctx.restore();
      var nl=wrap(sk.name,b.w-14,15);for(var q=0;q<nl.length;q++)txt(nl[q],b.x+b.w/2,b.y+128+q*17,15,'#ffffff','center');
      txt(sk.note,b.x+b.w/2,b.y+164,11,'#e8e0cc','center',false);
      if(eq)txt('EQUIPPED',b.x+b.w/2,b.y+192,18,'#7dff7a','center');
      else if(owned)txt('OWNED - click to equip',b.x+b.w/2,b.y+192,12,'#ffe66a','center',false);
      else if(sk.tan){tansanIcon(b.x+b.w/2-40,b.y+192,11);txt(String(sk.tan),b.x+b.w/2-22,b.y+192,20,(SAVE.tansan||0)>=sk.tan?'#9ff0ff':'#ff9a8a','left');}
      else{coinIcon(b.x+b.w/2-40,b.y+192,11);txt(String(sk.cost),b.x+b.w/2-22,b.y+192,20,SAVE.coins>=sk.cost?'#ffe27a':'#ff9a8a','left');}
    }else if(b.kind==='up'){
      var u=UPS[b.key],lv=SAVE.up[b.key],maxed=lv>=3;
      RR(b.x,b.y+7,b.w,b.h,20,'rgba(0,0,0,.4)',0);
      RR(b.x,b.y,b.w,b.h,20,'#363c52',hot?6:4);
      if(hot){ctx.strokeStyle='#ffd23f';ctx.lineWidth=5;rrect(b.x-4,b.y-4,b.w+8,b.h+8,23);ctx.stroke();}
      txt(u.name,b.x+22,b.y+28,22,'#ffffff','left');
      txt(u.desc,b.x+22,b.y+56,15,'#e8e0cc','left',false);
      for(var p=0;p<3;p++){circ(b.x+34+p*34,b.y+80,11,p<lv?'#43e07a':'#434b63',3);}
      var bx=b.x+b.w-190,aff=!maxed&&SAVE.coins>=u.costs[lv];
      RR(bx,b.y+22,168,56,16,maxed?'#434b63':(aff?'#2fcf6b':'#6b4a7a'),4);
      if(maxed)txt('MAX LEVEL',bx+84,b.y+50,20,'#ffe66a','center');
      else{txt('BUY',bx+34,b.y+50,20,'#fff','center');coinIcon(bx+88,b.y+50,12);txt(String(u.costs[lv]),bx+108,b.y+50,22,'#fff','left');}
    }else if(b.kind==='tab'){
      RR(b.x,b.y,b.w,b.h,14,hot?'#2fa6c9':'#27607a',3);txt(shopTab?'< BARYA SKINS':'TANSAN SKINS >',b.x+b.w/2,b.y+21,18,'#fff','center');
    }else drawBtn(b,hot);
  }
  txt('Click an item or use arrow keys + ENTER   |   ESC: back',W/2,712,14,'#e8e0cc','center',false);
}

/* ---------- tip pop-up (SDG 13) ---------- */
function drawBrief(){
  var br=G.brief,n=br.list.length,i;
  dim(.7);
  panel(150,60,980,600,'#2b3042');
  RR(150,60,980,70,26,'#d8452e',5);
  txt(n>1?'NEW DISASTERS IN THE '+ENV_NAMES[G.cfg.env]+' BIOME':'NEW DISASTER IN THE '+ENV_NAMES[G.cfg.env]+' BIOME',W/2,96,32,'#fff3d6','center');
  var cw=n>1?450:700,gap=30,x0=W/2-(n*cw+(n-1)*gap)/2;
  for(i=0;i<n;i++){
    var t=br.list[i],hz=HAZ[t.type],x=x0+i*(cw+gap);
    RR(x,148,cw,372,18,'#1d2130',3);
    RR(x+8,156,cw-16,38,12,hz.col,3);
    txt(hz.title,x+cw/2,176,24,'#10131c','center',false);
    RR(x+12,204,cw-24,n>1?120:140,12,'#171b28',3);
    drawDisasterScene(x+16,208,cw-32,n>1?112:132,t.type);
    var ty=n>1?340:360;
    var dl=wrap(hz.desc,cw-30,15);for(var q=0;q<dl.length;q++)txt(dl[q],x+cw/2,ty+q*19,15,'#ffe66a','center',false);
    var ey=ty+dl.length*19+10;
    var el=wrap('Effect: '+hz.hazard,cw-30,13);for(q=0;q<el.length&&q<3;q++)txt(el[q],x+cw/2,ey+q*17,13,'#faf3e0','center',false);
    var sy=ey+el.length*17+14;
    var sl2=wrap('SDG 13 TIP: '+t.tip,cw-30,13);for(q=0;q<sl2.length&&q<4;q++)txt(sl2[q],x+cw/2,sy+q*17,13,'#9fe0ff','center',false);
  }
  var oldt=br.old.length?'Also active from earlier biomes: '+br.old.map(function(k){return HAZ[k].label;}).join(', '):'This is the first disaster you will face. More arrive in later biomes!';
  txt(oldt,W/2,528,15,'#e8e0cc','center',false);
  txt('Disasters hit at random while you play (every 15-25 s). Stay in the safe zone when it is risky!',W/2,546,14,'#a8b4c8','center',false);
  var bs=buttonsFor('BRIEF');if(bs[0]){var ready=br.age>0.5;drawBtn(bs[0],ready&&(Math.floor(anim*2)%2===0||inRect(mouse,bs[0])));}
}

/* ---------- pause / results ---------- */
function critRow(y,ok,label,detail){
  circ(W/2-340,y,17,ok?'#43e07a':'#5a4f86',3);
  ctx.strokeStyle='#fff';ctx.lineWidth=4;ctx.beginPath();
  if(ok){ctx.moveTo(W/2-349,y);ctx.lineTo(W/2-342,y+7);ctx.lineTo(W/2-330,y-7);}
  else{ctx.moveTo(W/2-347,y-7);ctx.lineTo(W/2-333,y+7);ctx.moveTo(W/2-333,y-7);ctx.lineTo(W/2-347,y+7);}
  ctx.stroke();
  txt(label,W/2-308,y-6,22,ok?'#ffffff':'#b9b4e8','left');
  txt(detail,W/2-308,y+17,15,'#e8e0cc','left',false);
}
function drawOverlay(){
  var c=G.cfg,i;
  if(scene==='PAUSE'){
    dim(.65);panel(W/2-300,100,600,540,'#2b3042');
    txtT('PAUSED',W/2,162,38,'#ffffff','center');
    txt(G.endless?'WAVE '+RUN.wave+'  -  '+ENV_NAMES[c.env]+'  -  ENDLESS':'LEVEL '+G.level+'  -  '+ENV_NAMES[c.env],W/2,206,22,'#ffd23f','center');
    drawButtons();
    txt('ESC to resume   |   Arrow keys / mouse to choose',W/2,606,16,'#e8e0cc','center',false);
  }else if(scene==='BRIEF')drawBrief();
  else if(scene==='LEVEL_CLEAR'){
    var R_=G.result,ca=G.cAnim||0,pk=easeBack(ca/0.55);
    dim(.72);
    ctx.save();ctx.translate(W/2,365);ctx.scale(pk,pk);ctx.translate(-W/2,-365);
    panel(W/2-430,40,860,650,'#2b3042');
    var bounce=1+Math.max(0,Math.sin(ca*5))*0.04*Math.max(0,1-ca/2);
    ctx.save();ctx.translate(W/2,98);ctx.scale(bounce,bounce);txtT('LEVEL '+G.level+' CLEAR!',0,0,32,'#7dff7a','center');ctx.restore();
    if(R_.unlock)txt(R_.unlock,W/2,148,26,'#ffd23f','center');
    else txt('All cans knocked down!',W/2,148,20,'#f4ecd6','center',false);
    for(i=0;i<3;i++)starPop(i,W/2-130+i*130,228+(i===1?-14:0),i===1?58:48,R_.st[i]);
    var rows=Math.floor(clamp((ca-1.4)/0.25,0,3));
    if(rows>0)critRow(330,R_.st[0],'Reach the target score','Score '+G.score+'  /  goal '+G.goal);
    if(rows>1)critRow(380,R_.st[1],'Clear within the fast time','Time '+G.lt.toFixed(1)+'s  /  goal '+c.fastTime+'s');
    if(rows>2)critRow(430,R_.st[2],'No lives or pamato lost, or 2+ perfect retrievals','Lives lost '+G.livesLost+'  |  Pamato lost '+G.pamatoLost+'  |  Perfect retrievals '+G.perfectRetr);
    txt('SCORE  '+Math.round(G.score*clamp(ca/1.2,0,1))+'      BEST  '+SAVE.hi[G.level-1],W/2,488,26,'#ffffff','center');
    var aw=Math.round(R_.award*clamp((ca-1.8)/1,0,1));
    coinIcon(W/2-150,534+Math.sin(ca*8)*(aw<R_.award?3:0),16);
    txt('+'+aw+' COINS  ('+R_.stars+(R_.stars===1?' star':' stars')+')',W/2-124,534,26,'#ffe27a','left');
    if(G.coinsLvl>0)txt('+'+G.coinsLvl+' collected on the field',W/2,566,16,'#e8e0cc','center',false);
    drawButtons();
    ctx.restore();
    drawConfetti();
  }else if(scene==='LEVEL_FAILED'&&G.endless){
    dim(.72);panel(W/2-330,100,660,610,'#2b3042');
    txtT('RUN OVER',W/2,160,40,'#ff6b6b','center');
    txt(G.failReason,W/2,222,26,'#ffd23f','center');
    txt('You reached WAVE '+RUN.wave+' in the '+ENV_NAMES[c.env]+' biome',W/2,274,22,'#ffffff','center');
    txt('Run score  '+RUN.score+'      Stars earned  '+RUN.stars,W/2,318,22,'#ffffff','center');
    txt('Best wave here  '+SAVE.eb[RUN.env]+'      Best run score  '+SAVE.ebs[RUN.env],W/2,356,18,'#ffcf8a','center',false);
    coinIcon(W/2-90,398,14);txt('+'+G.coinsLvl+' coins kept this wave',W/2-68,398,20,'#ffe27a','left');
    txt('Total progress: '+survPts()+' pts / '+survStars()+' stars toward new biomes',W/2,432,15,'#e8e0cc','center',false);
    drawButtons();
  }else if(scene==='LEVEL_FAILED'){
    dim(.72);panel(W/2-330,100,660,610,'#2b3042');
    txt('LEVEL FAILED',W/2,160,62,'#ff6b6b','center');
    txt(G.failReason,W/2,226,30,'#ffd23f','center');
    var hint={"TIME'S UP":'Knock the can down faster before the clock hits 0.','OUT OF PAMATO':'Grab your slippers back - the Taya can take them!'}[G.failReason]||'Stay behind the toe line while the can is upright.';
    txt(hint,W/2,268,19,'#f4ecd6','center',false);
    txt('Cans down  '+G.hits+' / '+G.need,W/2,324,28,'#ffffff','center');
    txt('Score  '+G.score+'   (goal '+G.goal+')',W/2,366,24,'#ffffff','center');
    coinIcon(W/2-90,408,14);txt('+'+G.coinsLvl+' coins kept',W/2-68,408,22,'#ffe27a','left');
    drawButtons();
  }
}

/* =========================================================================
   NEW SCREENS: main menu + Top-10 leaderboard, profile hub, name entry, rules, catalogs
   ========================================================================= */
function lines(arr,x,y,size,lh,col,maxW){
  for(var i=0;i<arr.length;i++){
    var ls=maxW?wrap(arr[i],maxW,size):[arr[i]];
    for(var j=0;j<ls.length;j++){txt(ls[j],x,y,size,col||'#faf3e0','left',false);y+=lh;}
  }
  return y;
}
function drawBoard(x,y,w,h){
  panel(x,y,w,h,'#2b3042');
  RR(x,y,w,64,26,'#d8452e',5);
  txt('TOP 10 GLOBAL',x+w/2,y+26,30,'#fff','center');
  txt('total of best level scores',x+w/2,y+50,13,'#ffe0a0','center',false);
  for(var i=0;i<10;i++){
    var e=BOARD[i],ry=y+98+i*52,me=e&&SAVE&&e.name.toLowerCase()===SAVE.name.toLowerCase();
    RR(x+14,ry-22,w-28,46,12,me?'#3b6b45':(i%2?'#363c52':'#352a7e'),2.5);
    var mc=i===0?'#ffd23f':i===1?'#dfe6ee':i===2?'#e0955a':'#9aa0d8';
    circ(x+40,ry,16,mc,2.5);txt(String(i+1),x+40,ry,17,'#1d1b2e','center',false);
    if(e){
      txt(e.name,x+66,ry-7,19,me?'#9dffb0':'#fff','left',false);
      txt('L'+e.level+'  '+e.stars+' stars',x+66,ry+13,12,'#e8e0cc','left',false);
      txt(String(e.score),x+w-26,ry,22,'#ffe27a','right');
    }else txt('---',x+66,ry,18,'#6e6aa8','left',false);
  }
}
function drawProfiles(){
  drawWood();
  txt('PLAYER PROFILES',W/2,62,58,'#ffd23f','center');
  txt('Create or select a profile to track your scores, skins and level progress',W/2,112,19,'#f4ecd6','center',false);
  panel(200,138,880,566,'rgba(30,35,52,.9)');
  var bs=buttonsFor('PROFILES'),i;
  if(DB.profiles.length===0)txt('No profiles yet - press NEW to create one',W/2,300,26,'#e8e0cc','center',false);
  for(i=0;i<bs.length;i++){
    var b=bs[i],hot=(i===sel)||inRect(mouse,b);
    if(b.prof!==undefined){
      var p=DB.profiles[b.prof],on=pSel===b.prof;
      RR(b.x,b.y+5,b.w,b.h,16,'rgba(0,0,0,.4)',0);
      RR(b.x,b.y,b.w,b.h,16,on?'#2fcf6b':(hot?'#e8873a':'#66728e'),on?5:3.5);
      txt(p.name,b.x+20,b.y+b.h/2-8,26,'#fff','left');
      txt('SOLO  L'+topLevel(p.d1)+' / '+totalStars(p.d1)+' stars     CO-OP  L'+topLevel(p.d2)+' / '+totalStars(p.d2)+' stars',b.x+20,b.y+b.h/2+16,13,on?'#e8ffef':'#e8e0cc','left',false);
      txt(String(totalScore(p)),b.x+b.w-18,b.y+b.h/2,22,'#ffe27a','right');
      if(p.name===DB.active)txt('LAST USED',b.x+b.w-160,b.y+12,11,'#ffcf8a','right',false);
    }else drawBtn(b,hot);
  }
  if(DB.profiles.length>5){txt(pOff>0?'^ more above':'',W/2,160,14,'#ffe66a','center',false);txt(pOff+5<DB.profiles.length?'v more below  (mouse wheel / arrows)':'',W/2,506,14,'#ffe66a','center',false);}
  if(delConfirm&&DB.profiles[pSel])txt('Delete "'+DB.profiles[pSel].name+'" and all its progress?',W/2,488,22,'#ff9a8a','center');
  if(profMsgT>0){ctx.globalAlpha=Math.min(1,profMsgT*2);txt(profMsg,W/2,676,22,'#9dffb0','center');ctx.globalAlpha=1;}
  txt('ENTER: select   N: new   R: rename   X / DEL: delete   ESC: back',W/2,692,14,'#e8e0cc','center',false);
}
function drawName(){
  drawWood();
  panel(W/2-330,120,660,470,'#2b3042');
  txt(nameMode==='NEW'?'CREATE NEW PROFILE':'RENAME PROFILE',W/2,184,42,'#ffd23f','center');
  txt('Type your name, then press ENTER',W/2,232,20,'#f4ecd6','center',false);
  RR(W/2-240,270,480,86,18,'#171b28',4);
  var caret=Math.floor(anim*2)%2?'|':' ';
  txt(nameBuf+caret,W/2,314,48,'#ffffff','center');
  txt(nameBuf.length+' / 12',W/2+232,344,13,'#8a94aa','right',false);
  txt('Letters, numbers, space, _ and -   (2-12 characters)',W/2,388,16,'#e8e0cc','center',false);
  if(nameErr)txt(nameErr,W/2,426,22,'#ff9a8a','center');
  if(DB.profiles.length===0)txt('A profile is required before you can play.',W/2,556,16,'#ffe66a','center',false);
  var bs=buttonsFor('NAME');
  for(var i=0;i<bs.length;i++)drawBtn(bs[i],i===sel||inRect(mouse,bs[i]));
}
function drawRules(){
  drawWood();
  txt('RULES & INSTRUCTIONS',W/2,52,48,'#ffd23f','center');
  panel(70,170,1140,460,'rgba(30,35,52,.94)');
  var bs=buttonsFor('RULES'),i,x=100,y=206;
  for(i=0;i<bs.length;i++)drawBtn(bs[i],i===sel||inRect(mouse,bs[i]));
  if(rulesTab===0){
    txt('HOW TO PLAY',x,y,26,'#f5c542','left');
    y=lines(['Knock the tin CAN down with your pamato (slipper) from the SAFE ZONE. When it falls, grab your slippers - but the TAYA stands the can back up and chases you!',
      'Stay outside the safe zone with the can upright and the Taya tags you. Tagged = lose a life.'],x,y+32,17,22,'#faf3e0',1090);
    RR(x,y+8,540,252,16,'#363c52',3);RR(x+560,y+8,520,252,16,'#363c52',3);
    txt('PLAYER 1',x+20,y+32,22,'#38b6ff','left');
    y=lines(['W A S D  - move freely (any direction)','SPACE  - 1st press: start sighting','A / D  - fine-tune the throw angle','SPACE  - 2nd press: lock angle and throw','E - active skill     SPACE is Player 1 only'],x+20,y+62,16,26,'#faf3e0');
    txt('PLAYER 2',x+580,y-132,22,'#ffc533','left');
    lines(['ARROWS  - move freely (any direction)','ENTER  - 1st press: start sighting','LEFT / RIGHT  - fine-tune the throw angle','ENTER  - 2nd press: lock angle and throw','R-SHIFT - active skill     P / ESC  pause     M  mute'],x+580,y-102,16,26,'#faf3e0');
    txt('While sighting, the power meter swings: CENTER = exact distance to the can, LEFT = short, RIGHT = far. Move freely in the safe zone before you sight.',x,y+64,15,'#ffe66a','left',false);
  }else if(rulesTab===1){
    txt('SCORING & STARS',x,y,26,'#f5c542','left');
    y=lines(['Can hit: 100 points. PERFECT meter (green) +50. Every hit adds 0.25 to the COMBO multiplier (max x4); a miss lowers it, a tag resets it.',
      'Streak bonus: +25 per extra consecutive hit. Perfect retrieval (grab a pamato right next to the Taya): +25.',
      'SCORE and COINS are separate. Score ranks you on the leaderboard (sum of your best score on every level).',
      'Coins: +10 for each coin on the field (+20 with Double Coins). Star awards: 1 star = 50, 2 stars = 100, 3 stars = 200 coins.',
      'Spend coins on pamato skins, permanent upgrades and Pinoy characters with passive skills. MYSTERY POGS (?) give a random buff or a TRAP!'],x,y+32,17,22,'#faf3e0',1090);
    txt('3 STARS PER LEVEL',x,y+12,22,'#ffd23f','left');
    lines(['1 star  -  reach the target score','2 stars  -  clear before the fast-time limit','3 stars  -  zero lives and pamato lost, OR two perfect retrievals','Beat the timer: running out of time FAILS the level (it never clears it).','Biomes are STAR-GATED: School 12, City 30, Beach 50, Forest 72, Haunted 96 total stars.'],x,y+44,16,24,'#faf3e0');
  }else{
    txt('SOLO vs ENDLESS CO-OP',x,y,26,'#f5c542','left');
    txt('SOLO',x,y+40,22,'#38b6ff','left');
    lines(['60 levels in 6 biomes, only 3 pamato, 3 lives. Run out of pamato or lives = LEVEL FAILED. Solo and Co-op keep completely SEPARATE saves.'],x,y+68,16,22,'#faf3e0',1090);
    txt('2-PLAYER ENDLESS SURVIVAL',x,y+120,22,'#ffc533','left');
    lines(['Pick a biome (unlocked by cumulative points OR stars) and survive wave after wave - the Taya gets faster every wave. 4 shared lives, +1 every 5 waves.',
      'After every wave you may spend coins on ONE of 3 random upgrades. Overtime on a wave costs a life. Run out of lives or pamato = RUN OVER.',
      'SYNC RETRIEVAL: both partners grab a pamato within 2.5s = +100. Each biome adds a new disaster (briefed on its first level); they hit every 15-25s.',
      'SPACE = Player 1 only, ENTER = Player 2 only. Active skills: E (P1) and RIGHT SHIFT (P2).'],x,y+148,16,22,'#faf3e0',1090);
  }
}
function drawCatPog(){
  drawWood();
  txt('POG CATALOG',W/2,48,46,'#ffd23f','center');
  txt('Named pogs show their real effect. ? pogs are a gamble: mostly buffs, sometimes traps. Touch or hit them with a slipper!',W/2,98,17,'#f4ecd6','center',false);
  var bs=buttonsFor('CAT_POG'),i;
  for(i=0;i<bs.length;i++){
    var b=bs[i],hot=(i===sel)||inRect(mouse,b);
    if(b.cat!==undefined){
      var on=pogSel===b.cat,ty=POG_ORDER[b.cat],d=POGS[ty];
      RR(b.x,b.y+5,b.w,b.h,16,'rgba(0,0,0,.4)',0);RR(b.x,b.y,b.w,b.h,16,on?(d.trap?'#c0392b':'#2fcf6b'):(hot?'#e8873a':'#566079'),on?5:3.5);
      drawPog(ty,b.x+34,b.y+b.h/2,22,anim*2+b.cat);
      txt(d.name,b.x+72,b.y+b.h/2-8,21,'#fff','left');
      txt(d.dur?d.dur+' seconds':(t_inst(ty)?'instant':'next throw'),b.x+72,b.y+b.h/2+14,12,on?'#ffffff':'#e8e0cc','left',false);
      RR(b.x+b.w-84,b.y+14,70,28,10,d.trap?'#ff5a4a':'#43e07a',2.5);txt(d.trap?'TRAP':'BUFF',b.x+b.w-49,b.y+28,15,'#1d1b2e','center',false);
    }else drawBtn(b,hot);
  }
  var t=POG_ORDER[pogSel],d=POGS[t];
  panel(520,138,700,494,d.trap?'rgba(70,20,40,.94)':'rgba(30,35,52,.94)');
  drawMystery(640,260,62,anim*1.4);
  txt('=',720,262,44,'#fff','center');
  drawPog(t,800,260,62,anim*1.6);
  txt(d.name,W/2+250,370,38,d.bg,'center');
  txt(d.trap?'TRAP EFFECT':'BUFF EFFECT',W/2+250,404,17,d.trap?'#ff9a8a':'#7dff7a','center');
  lines([d.desc],580,438,19,26,'#faf3e0',600);
  txt('TIP: '+d.tip,580,548,17,'#ffe66a','left',false);
  txt('Duration: '+(d.dur?d.dur+' seconds':(t_inst(t)?'instant':'one throw')),580,584,17,'#f5c542','left',false);
  txt('Pogs spawn randomly on the field and vanish after 16 seconds.',580,612,14,'#e8e0cc','left',false);
}
function drawCatDis(){
  drawWood();
  txt('PHILIPPINE DISASTER CATALOG',W/2,50,46,'#ffd23f','center');
  txt('One new disaster per biome, introduced on its first level. They stack in later biomes.',W/2,96,17,'#f4ecd6','center',false);
  var bs=buttonsFor('CAT_DIS'),i;
  for(i=0;i<bs.length;i++){
    var b=bs[i],hot=(i===sel)||inRect(mouse,b);
    if(b.cat!==undefined){
      var tp=HAZ_ORDER[b.cat],on=disSel===b.cat;
      RR(b.x,b.y+5,b.w,b.h,16,'rgba(0,0,0,.4)',0);RR(b.x,b.y,b.w,b.h,16,on?HAZ[tp].col:(hot?'#e8873a':'#566079'),on?5:3.5);
      txt(HAZ[tp].label,b.x+20,b.y+b.h/2,24,on?'#1d1b2e':'#fff','left',!on);
    }else drawBtn(b,hot);
  }
  var t=HAZ_ORDER[disSel],h=HAZ[t];
  panel(410,130,810,500,'rgba(30,35,52,.94)');
  RR(430,150,360,300,16,'#171b28',3);
  drawDisasterScene(434,154,352,292,t);
  txt(h.title,810,172,28,h.col,'left');
  lines([h.desc],810,206,17,23,'#faf3e0',390);
  txt('FIELD EFFECT',810,306,16,'#ffd23f','left',false);
  lines([h.hazard],810,328,15,21,'#c9f0ff',390);
  txt('Introduced in the '+BIOMES[hazBiome(t)].name+' biome  -  '+h.agency,810,404,14,'#ffcf8a','left',false);
  txt('When: '+h.season,810,428,14,'#e8e0cc','left',false);
  RR(430,466,770,142,14,'#2a62c9',3);
  txt('SDG 13 SAFETY TIP',450,488,16,'#fff','left',false);
  var tp2=(TIPS[t]||['Stay safe!'])[0];
  lines([tp2],450,516,17,24,'#ffffff',730);
}

function portrait(id,x,y,sc){
  var c=CHARS[id];
  person(x,y,{face:1,skin:c.skin,hair:c.hair,shirt:c.shirt,stripe:'#fff',pants:c.pants,hat:c.hat==='salakot'?'salakot':'',band:c.hat==='band'?'#fff':'',ring:'#ffcf8a',scale:sc});
}
function drawChars(){
  drawWood();
  txtT('CHARACTERS',W/2,44,30,'#f5c542','center');coinPill(W-40,46);
  txt('Unlock a hero, then spend coins on 4 skills each - 3 passive and 1 ACTIVE (E for P1, R-SHIFT for P2). TAB = P1/P2.',W/2,88,16,'#f4ecd6','center',false);
  var bs=buttonsFor('CHARS'),i,cid=CHAR_ORDER[charSel],c=CHARS[cid],own=SAVE.chars.indexOf(cid)>=0,lvs=SAVE.sk[cid];
  panel(470,118,750,520,'rgba(30,35,52,.94)');
  portrait(cid,570,260,3.6);
  txt(c.name,680,150,40,'#ffd23f','left');
  txt(c.title,680,186,16,'#ffcf8a','left',false);
  lines([c.bio],680,222,16,22,'#faf3e0',520);
  for(i=0;i<bs.length;i++){
    var b=bs[i],hot=(i===sel)||inRect(mouse,b);
    if(b.cat!==undefined){
      var id=CHAR_ORDER[b.cat],o2=SAVE.chars.indexOf(id)>=0,on=charSel===b.cat;
      RR(b.x,b.y+5,b.w,b.h,16,'rgba(0,0,0,.4)',0);RR(b.x,b.y,b.w,b.h,16,on?'#2fcf6b':(hot?'#e8873a':'#566079'),on?5:3.5);
      portrait(id,b.x+40,b.y+70,1.8);
      txt(CHARS[id].name,b.x+84,b.y+28,22,'#fff','left');
      txt(CHARS[id].skills[2].n+' (active)',b.x+84,b.y+54,13,on?'#e8ffef':'#e8e0cc','left',false);
      if(o2){for(var p=0;p<2;p++)if(SAVE.sel[p]===id){RR(b.x+b.w-48-p*44,b.y+10,38,24,8,p?'#ffc533':'#38b6ff',2.5);txt('P'+(p+1),b.x+b.w-29-p*44,b.y+23,14,'#171b28','center',false);}}
      else{coinIcon(b.x+b.w-70,b.y+24,11);txt(String(CHARS[id].cost),b.x+b.w-54,b.y+24,18,SAVE.coins>=CHARS[id].cost?'#ffe27a':'#ff9a8a','left');}
    }else if(b.node!==undefined){
      var k=b.node,sk=c.skills[k],lv=lvs[k],act=sk.t==='ACTIVE',cost=lv<3?SKILL_COST[lv]+(act?60:0):0;
      RR(b.x,b.y+5,b.w,b.h,16,'rgba(0,0,0,.4)',0);
      RR(b.x,b.y,b.w,b.h,16,lv>=3?'#2c6b4f':(own&&hot?'#c8702e':(act?'#4a526c':'#363c52')),hot?5:3.5);
      txt(sk.n,b.x+b.w/2,b.y+20,16,act?'#ffd23f':'#fff','center');
      txt(act?'ACTIVE  ['+ACT_NAME[charSlot]+']':'PASSIVE',b.x+b.w/2,b.y+40,11,act?'#f5c542':'#a8b4c8','center',false);
      for(var q=0;q<3;q++)circ(b.x+b.w/2-24+q*24,b.y+60,8,q<lv?'#ffd23f':'#1d2130',2.5);
      var shown=lv>0?sk.d[lv-1]:'Not learned';
      var ls=wrap((lv>0?'NOW: ':'')+shown,b.w-20,12);for(var r=0;r<ls.length&&r<3;r++)txt(ls[r],b.x+b.w/2,b.y+84+r*15,12,'#faf3e0','center',false);
      if(lv<3){var nl=wrap('NEXT: '+sk.d[lv],b.w-20,11);for(r=0;r<nl.length&&r<3;r++)txt(nl[r],b.x+b.w/2,b.y+124+r*13,11,'#a8b4c8','center',false);}
      if(lv>=3)txt('MAXED',b.x+b.w/2,b.y+b.h-16,14,'#7dff7a','center');
      else{coinIcon(b.x+b.w/2-26,b.y+b.h-14,8);txt(String(cost),b.x+b.w/2-14,b.y+b.h-14,14,SAVE.coins>=cost&&own?'#ffe27a':'#7c869c','left',false);}
    }else drawBtn(b,hot);
  }
  if(shopMsgT>0){ctx.globalAlpha=Math.min(1,shopMsgT*2);txt(shopMsg,W/2+250,330,18,'#fff3a0','center');ctx.globalAlpha=1;}
  txt('P1: '+CHARS[SAVE.sel[0]].name+'     P2: '+CHARS[SAVE.sel[1]].name,W/2+250,624,18,'#ffffff','center');
}

/* ---------- nostalgic Pinoy sari-sari look: wooden planks, banderitas, painted signboards ---------- */
function drawWood(){
  ctx.drawImage(bgFor(menuBgEnv),0,0,W,H);
  ctx.fillStyle='rgba(10,14,26,.5)';ctx.fillRect(0,0,W,H);
  var vg=ctx.createRadialGradient(W/2,H/2,H*.35,W/2,H/2,H*.95);vg.addColorStop(0,'rgba(0,0,0,0)');vg.addColorStop(1,'rgba(6,8,16,.55)');ctx.fillStyle=vg;ctx.fillRect(0,0,W,H);
}
var menuBgEnv=0;
function sign(x,y,w,h){
  RR(x+6,y+10,w,h,22,'rgba(0,0,0,.4)',0);
  RR(x,y,w,h,22,'#232838',5);
  RR(x+10,y+10,w-20,h-20,16,'#303650',3);
  ctx.strokeStyle='rgba(232,135,58,.7)';ctx.lineWidth=2;rrect(x+18,y+18,w-36,h-36,12);ctx.stroke();
  circ(x+14,y+14,5,'#e8873a',2);circ(x+w-14,y+14,5,'#e8873a',2);circ(x+14,y+h-14,5,'#e8873a',2);circ(x+w-14,y+h-14,5,'#e8873a',2);
}
function drawSurvival(){
  drawWood();
  txtT('ENDLESS CO-OP SURVIVAL',W/2,44,28,'#f5c542','center');coinPill(W-40,46);
  txt('Pick a biome and survive wave after wave. Unlock biomes with cumulative points OR stars.',W/2,82,17,'#f4ecd6','center',false);
  var bs=buttonsFor('SURVIVAL'),i;
  var b0=BIOMES[survSel],g=SURV_GATE[survSel],open=survOpen(survSel);
  panel(150,172,980,372,'rgba(30,35,52,.94)');
  ctx.save();rrect(176,196,430,242,16);ctx.clip();ctx.drawImage(bgFor(survSel),0,0,W,590,176,196,430,242);
  if(!open){ctx.fillStyle='rgba(20,8,0,.62)';ctx.fillRect(176,196,430,242);}ctx.restore();
  RR(176,196,430,242,16,null,4);
  if(!open)lockIcon(391,317,2.4);
  txt(b0.name,640,214,40,b0.col,'left');
  txt(open?'OPEN - ready to survive':'LOCKED',640,252,20,open?'#7dff7a':'#ff9a8a','left');
  txt('BEST WAVE  '+SAVE.eb[survSel]+'      BEST RUN SCORE  '+SAVE.ebs[survSel],640,288,17,'#faf3e0','left',false);
  txt('TO UNLOCK (reach either one):',640,326,16,'#f5c542','left',false);
  var p=survPts(),st=survStars();
  RR(640,342,420,18,9,'#171b28',3);RR(643,345,Math.max(0,414*Math.min(1,g[0]?p/g[0]:1)),12,6,'#ffd23f',0);
  txt(g[0]+' cumulative points  ('+Math.min(p,g[0])+')',640,376,14,'#e8e0cc','left',false);
  RR(640,392,420,18,9,'#171b28',3);RR(643,395,Math.max(0,414*Math.min(1,g[1]?st/g[1]:1)),12,6,'#43e07a',0);
  txt(g[1]+' cumulative stars  ('+Math.min(st,g[1])+')',640,426,14,'#e8e0cc','left',false);
  txt('YOUR TOTAL:  '+p+' pts   '+st+' stars',640,486,18,'#ffffff','left');
  txt('Waves get faster every round.',176,466,14,'#a8b4c8','left',false);
  txt('After each wave pick 1 of 3 coin upgrades.',176,486,14,'#a8b4c8','left',false);
  txt('Biome disasters hit mid-game. 4 shared lives (+1 / 5 waves)',176,506,14,'#a8b4c8','left',false);
  for(i=0;i<bs.length;i++){
    var b=bs[i],hot=(i===sel)||inRect(mouse,b);
    if(b.sb!==undefined){
      var o=survOpen(b.sb),on=survSel===b.sb;
      RR(b.x,b.y+5,b.w,b.h,14,'rgba(0,0,0,.4)',0);RR(b.x,b.y,b.w,b.h,14,on?BIOMES[b.sb].col2:(hot?'#e8873a':'#566079'),on?5:3.5);
      txt(BIOMES[b.sb].name,b.x+b.w/2+(o?0:10),b.y+b.h/2,15,on?'#fff':'#e8e0cc','center',false);
      if(!o)lockIcon(b.x+16,b.y+b.h/2,0.8);
    }else drawBtn(b,hot);
  }
  if(selMsgT>0){ctx.globalAlpha=Math.min(1,selMsgT*2);txt(selMsg,W/2,664,20,'#ffb0a0','center');ctx.globalAlpha=1;}
  txt('A / D or arrows: choose biome     ENTER / SPACE: start     ESC: back',W/2,700,14,'#e8e0cc','center',false);
}
function upgIcon(id,x,y){
  var cols={spd:'#43e07a',life:'#ff4f6d',ammo:'#ff8a2b',wind:'#6aa7ff',meter:'#ffd23f',pick:'#b27cff',inv:'#9fe9ff',coin:'#ffd23f',slow:'#e8873a',perf:'#7dff7a',stun:'#ff9ed0'};
  circ(x,y,44,cols[id]||'#fff',4);
  if(id==='life'){heartPath(x-20,y-24,40);paint('#fff',3);}
  else if(id==='ammo'){ctx.save();ctx.translate(x,y);slipperShape(1.1,false,1.5);ctx.restore();}
  else txt({spd:'>>',wind:'~',meter:'||',pick:'(+)',inv:'[ ]',coin:'$',slow:'zZ',perf:'*',stun:'!!'}[id]||'?',x,y,34,'#171b28','center',false);
}
function drawUpgrade(){
  drawWood();
  var bs=buttonsFor('UPGRADE'),i,R_=G.result;
  var ca=G.cAnim||0,bn=1+Math.max(0,Math.sin(ca*5))*0.05*Math.max(0,1-ca/2);
  ctx.save();ctx.translate(W/2,52);ctx.scale(bn,bn);txtT('WAVE '+RUN.wave+' CLEARED!',0,0,30,'#7dff7a','center');ctx.restore();
  for(i=0;i<3;i++)starPop(i,W/2-70+i*70,112,i===1?26:22,R_.st[i]);
  txt('+'+R_.award+' coins   |   Run score '+RUN.score+'   |   Lives '+G.lives,W/2,156,18,'#ffe27a','center');
  coinPill(W-40,46);
  for(i=0;i<3;i++){
    var b=bs[i],ch=RUN.choices[i],u=UPG[ch.id],hot=(i===sel)||inRect(mouse,b),taken=RUN.picked===i,locked=RUN.picked>=0&&!taken,can=SAVE.coins>=ch.cost;
    RR(b.x,b.y+7,b.w,b.h,22,'rgba(0,0,0,.45)',0);
    RR(b.x,b.y-(hot&&!locked?4:0),b.w,b.h,22,taken?'#2c6b4f':(locked?'#2a2f40':(hot?'#e8873a':'#434b63')),hot?5:3.5);
    txt('CHOICE '+(i+1),b.x+b.w/2,b.y+26,16,'#f5c542','center',false);
    upgIcon(ch.id,b.x+b.w/2,b.y+88);
    txt(u.n,b.x+b.w/2,b.y+156,22,'#fff','center');
    var ls=wrap(u.d,b.w-40,15);for(var q=0;q<ls.length;q++)txt(ls[q],b.x+b.w/2,b.y+190+q*20,15,'#faf3e0','center',false);
    txt('Level '+(RUN.up[ch.id]||0)+(taken?'':' -> '+((RUN.up[ch.id]||0)+1)),b.x+b.w/2,b.y+238,14,'#a8b4c8','center',false);
    if(taken)txt('PURCHASED!',b.x+b.w/2,b.y+272,22,'#7dff7a','center');
    else{coinIcon(b.x+b.w/2-34,b.y+272,12);txt(String(ch.cost),b.x+b.w/2-18,b.y+272,24,can?'#ffe27a':'#ff9a8a','left');}
  }
  var nb=bs[3];drawBtn(nb,sel===3||inRect(mouse,nb));
  var keys=[];for(var k in RUN.up)if(RUN.up[k])keys.push(UPG[k].n+' x'+RUN.up[k]);
  txt(keys.length?'RUN UPGRADES:  '+keys.join('  -  '):'No run upgrades yet - pick one above!',W/2,646,15,'#e8e0cc','center',false);
  txt('A / D or arrows: choose     ENTER / SPACE: buy or continue     1-2-3: quick buy',W/2,676,14,'#a8b4c8','center',false);
  drawConfetti();
  if(selMsgT>0){ctx.globalAlpha=Math.min(1,selMsgT*2);txt(selMsg,W/2,530,18,'#ff9a8a','center');ctx.globalAlpha=1;}
}

function drawBoardScreen(){
  drawWood();
  drawBoard(W/2-260,26,520,610);
  var bs=buttonsFor('BOARD');drawBtn(bs[0],true);
}

function t_inst(t){return t==='SPARE'||t==='FREEZE';}

""";

    private static final String JS_RENDER = """
/* =========================================================================
   RENDERING - clean hand-drawn arcade look: thick ink outlines, flat vibrant fills
   ========================================================================= */
var INK='#10131c';
var FONT='"Jersey 10","Trebuchet MS","Segoe UI",sans-serif',TFONT='"Press Start 2P","Courier New",monospace',FSC=1.22;
ctx.lineJoin='round';ctx.lineCap='round';

function rrect(x,y,w,h,r){
  var c=Math.max(2,Math.min(Math.round(r*0.45),8));
  ctx.beginPath();ctx.moveTo(x+c,y);ctx.lineTo(x+w-c,y);ctx.lineTo(x+w,y+c);ctx.lineTo(x+w,y+h-c);ctx.lineTo(x+w-c,y+h);ctx.lineTo(x+c,y+h);ctx.lineTo(x,y+h-c);ctx.lineTo(x,y+c);ctx.closePath();
}
var SHB=null;
function shade(hex,k){
  var r=parseInt(hex.substr(1,2),16),g=parseInt(hex.substr(3,2),16),b=parseInt(hex.substr(5,2),16),t=k>0?255:0,a=Math.abs(k);
  return 'rgb('+Math.round(r+(t-r)*a)+','+Math.round(g+(t-g)*a)+','+Math.round(b+(t-b)*a)+')';
}
/* pixel look: flat fills + a crisp 1-pixel dark edge (UI) or thin edge (sprites) */
function paint(fill,lw){
  if(fill){ctx.fillStyle=fill;ctx.fill();}
  SHB=null;
  if(lw!==0){
    var sc=Math.abs(ctx.getTransform().a)/PXS;
    ctx.lineWidth=sc>1.2?(lw||3)*0.5:(lw>=4?4:2);
    ctx.strokeStyle='rgba(12,14,22,.9)';ctx.stroke();
  }
}
function RR(x,y,w,h,r,fill,lw){
  rrect(x,y,w,h,r);paint(fill,lw);
  if(typeof fill==='string'&&fill.charAt(0)==='#'&&h>=16&&w>=16&&Math.abs(ctx.getTransform().a)/PXS<1.2){
    var c=Math.max(2,Math.min(Math.round(r*0.45),8));
    ctx.fillStyle='rgba(255,255,255,.2)';ctx.fillRect(x+c+2,y+3,w-2*c-4,2);
    ctx.fillStyle='rgba(0,0,0,.25)';ctx.fillRect(x+c+2,y+h-5,w-2*c-4,2);
  }
}
function circ(x,y,r,fill,lw){ctx.beginPath();ctx.arc(x,y,r,0,6.2832);paint(fill,lw);}
function shadowEll(x,y,rx,ry,a){ctx.fillStyle='rgba(20,16,40,'+(a||0.28)+')';ctx.beginPath();ctx.ellipse(x,y,rx,ry,0,0,6.2832);ctx.fill();}
function txtT(s,x,y,size,col,align,outline){TQ.push({s:s,x:x,y:y,size:size,col:col||'#fff',al:align||'left',ol:outline!==false,tf:true,M:ctx.getTransform(),a:ctx.globalAlpha});}
function txt(s,x,y,size,col,align,outline){
  TQ.push({s:s,x:x,y:y,size:size,col:col||'#fff',al:align||'left',ol:outline!==false,M:ctx.getTransform(),a:ctx.globalAlpha});
}
function wrap(s,maxW,size){
  ctx.font=Math.round(size*FSC)+'px '+FONT;
  var words=s.split(' '),lines=[],cur='';
  for(var i=0;i<words.length;i++){
    var t=cur?cur+' '+words[i]:words[i];
    if(ctx.measureText(t).width>maxW&&cur){lines.push(cur);cur=words[i];}else cur=t;
  }
  if(cur)lines.push(cur);
  return lines;
}
function heartPath(x,y,s){
  ctx.beginPath();ctx.moveTo(x,y+s*.35);
  ctx.bezierCurveTo(x-s*.1,y-s*.1,x-s*.6,y-s*.05,x-s*.55,y+s*.3);
  ctx.bezierCurveTo(x-s*.5,y+s*.65,x-s*.1,y+s*.75,x,y+s*.95);
  ctx.bezierCurveTo(x+s*.1,y+s*.75,x+s*.5,y+s*.65,x+s*.55,y+s*.3);
  ctx.bezierCurveTo(x+s*.6,y-s*.05,x+s*.1,y-s*.1,x,y+s*.35);ctx.closePath();
}
function starPath(x,y,r){
  ctx.beginPath();
  for(var i=0;i<10;i++){var a=-Math.PI/2+i*Math.PI/5,rr=i%2?r*.45:r;ctx.lineTo(x+Math.cos(a)*rr,y+Math.sin(a)*rr);}
  ctx.closePath();
}
function drawStar(x,y,r,on){
  starPath(x,y,r);paint(on?'#ffd23f':'#434b63',Math.max(2,r/6));
  if(on){starPath(x-r*.12,y-r*.12,r*.5);ctx.fillStyle='rgba(255,255,255,.45)';ctx.fill();}
}
function coinIcon(x,y,r){
  circ(x,y,r,'#ffc928',Math.max(2,r/5));circ(x,y,r*.66,'#ffe27a',0);
  ctx.strokeStyle='#b8860b';ctx.lineWidth=Math.max(1.5,r/8);ctx.beginPath();ctx.arc(x,y,r*.66,0,6.3);ctx.stroke();
  ctx.fillStyle='#b8860b';ctx.fillRect(x-r*.1,y-r*.35,r*.2,r*.7);
}

/* ---------- original arena backgrounds (cached) ---------- */
function wob(g,rng,x,y,n,sx,sy){g.beginPath();g.moveTo(x,y);for(var i=0;i<n;i++){x+=(rng()-.4)*sx;y+=(rng()-.5)*sy;g.lineTo(x,y);}g.stroke();}
function buildBG(env,lineY,wd){
  var W=wd||AW;
  if(env>=3)return buildBGnew(env,lineY,W);
  var c=document.createElement('canvas');c.width=W;c.height=H;
  var g=c.getContext('2d'),rng=mulberry(env*977+5),i,j,gr;
  g.lineJoin='round';g.lineCap='round';
  function box(x,y,w,h,r,fill,lw){g.beginPath();g.moveTo(x+r,y);g.arcTo(x+w,y,x+w,y+h,r);g.arcTo(x+w,y+h,x,y+h,r);g.arcTo(x,y+h,x,y,r);g.arcTo(x,y,x+w,y,r);g.closePath();g.fillStyle=fill;g.fill();g.lineWidth=lw||3;g.strokeStyle=INK;g.stroke();}
  if(env===0){
    gr=g.createLinearGradient(0,0,0,H);gr.addColorStop(0,'#9db4c9');gr.addColorStop(1,'#7e96ad');
    g.fillStyle=gr;g.fillRect(0,0,W,H);
    for(i=0;i<80;i++){g.fillStyle=rng()<.5?'rgba(255,255,255,.07)':'rgba(30,40,70,.07)';g.beginPath();g.ellipse(rng()*W,130+rng()*560,30+rng()*80,8+rng()*22,0,0,6.3);g.fill();}
    g.strokeStyle='rgba(40,50,80,.33)';g.lineWidth=3;
    for(i=0;i<20;i++)wob(g,rng,rng()*W,150+rng()*420,7,40,26);
    g.strokeStyle='rgba(255,255,255,.33)';g.lineWidth=4;g.setLineDash([14,14]);
    for(i=0;i<6;i++){g.beginPath();g.arc(80+rng()*(W-160),220+rng()*300,34+rng()*30,0,6.3);g.stroke();}
    g.setLineDash([]);
    var x=-10,cols=['#ff7b54','#ffb26b','#4ecdc4','#6c8cff','#f9d56e','#d8452e'],roofs=['#c4412a','#d98a2f','#2a9d95','#3f56c9','#c9a02d','#c4407f'];
    while(x<W){var w=110+Math.floor(rng()*50),hh=70+Math.floor(rng()*28),k=Math.floor(rng()*cols.length);
      box(x,128-hh,w,hh,6,cols[k]);
      g.beginPath();g.moveTo(x-6,128-hh+2);g.lineTo(x+w/2,128-hh-30);g.lineTo(x+w+6,128-hh+2);g.closePath();g.fillStyle=roofs[k];g.fill();g.lineWidth=3;g.strokeStyle=INK;g.stroke();
      box(x+14,128-hh+20,24,24,4,'#fff1b8');box(x+w-40,128-hh+20,24,24,4,'#fff1b8');
      x+=w+4;}
    box(-10,120,W+20,18,4,'#d8d0c0');
    // sidewalk under the safe zone
    box(-10,H-44,W+20,54,6,'#d8d0c0');
    for(i=0;i<W;i+=96){g.strokeStyle='rgba(29,27,46,.4)';g.lineWidth=2;g.beginPath();g.moveTo(i,H-44);g.lineTo(i,H);g.stroke();}
    for(i=0;i<16;i++){var px=rng()*W;g.fillStyle='#69c35a';g.beginPath();g.ellipse(px,134,16,8,0,0,6.3);g.fill();g.lineWidth=2;g.strokeStyle=INK;g.stroke();}
  }else if(env===1){
    gr=g.createLinearGradient(0,0,0,H);gr.addColorStop(0,'#f0b86a');gr.addColorStop(1,'#e39a4a');
    g.fillStyle=gr;g.fillRect(0,0,W,H);
    g.fillStyle='#5dbb63';g.fillRect(0,0,W,128);
    box(40,10,W-80,100,8,'#f7e7b8');
    box(40,10,W-80,24,8,'#e4572e');
    for(i=0;i<Math.floor((W-100)/125);i++){box(70+i*125,48,64,46,5,'#7ab8ff');g.strokeStyle=INK;g.lineWidth=2;g.beginPath();g.moveTo(102+i*125,48);g.lineTo(102+i*125,94);g.stroke();}
    box(W/2-100,34,200,32,6,'#2f9e6a');
    g.font='800 22px '+FONT;g.textAlign='center';g.textBaseline='middle';g.fillStyle='#fff';g.fillText('PAARALAN',W/2,51);
    // painted court
    g.strokeStyle='rgba(255,255,255,.9)';g.lineWidth=6;
    g.strokeRect(40,150,W-80,lineY-150-20);
    g.beginPath();g.moveTo(40,(150+lineY-20)/2);g.lineTo(W-40,(150+lineY-20)/2);g.stroke();
    g.beginPath();g.arc(W/2,(150+lineY-20)/2,100,0,6.3);g.stroke();
    g.beginPath();g.arc(W/2,150,230,0,Math.PI);g.stroke();
    g.beginPath();g.arc(W/2,lineY-20,230,Math.PI,Math.PI*2);g.stroke();
    g.strokeStyle='rgba(255,230,120,.8)';g.setLineDash([18,14]);g.strokeRect(20,138,W-40,lineY-138);g.setLineDash([]);
    for(i=0;i<60;i++){g.fillStyle='rgba(255,255,255,.08)';g.beginPath();g.ellipse(rng()*W,140+rng()*560,16+rng()*50,6+rng()*18,0,0,6.3);g.fill();}
    // paved safe zone + hedges
    g.fillStyle='#d7c6a0';g.fillRect(0,lineY,W,H-lineY);
    g.strokeStyle='rgba(29,27,46,.25)';g.lineWidth=2;for(i=0;i<W;i+=80){g.beginPath();g.moveTo(i,lineY);g.lineTo(i,H);g.stroke();}
    for(i=0;i<W;i+=70){g.fillStyle='#3f9f4f';g.beginPath();g.arc(i+30,H+4,36,0,6.3);g.fill();g.lineWidth=3;g.strokeStyle=INK;g.stroke();}
  }else{
    gr=g.createLinearGradient(0,0,0,H);gr.addColorStop(0,'#3a3763');gr.addColorStop(1,'#56527f');
    g.fillStyle=gr;g.fillRect(0,0,W,H);
    // road lane markings (horizontal)
    g.strokeStyle='#ffd166';g.lineWidth=6;g.setLineDash([34,26]);
    g.beginPath();g.moveTo(0,(150+lineY)/2);g.lineTo(W,(150+lineY)/2);g.stroke();
    g.strokeStyle='rgba(255,255,255,.6)';g.lineWidth=4;
    g.beginPath();g.moveTo(0,260);g.lineTo(W,260);g.moveTo(0,lineY-90);g.lineTo(W,lineY-90);g.stroke();
    g.setLineDash([]);
    g.fillStyle='rgba(255,255,255,.8)';for(i=40;i<W-40;i+=64)g.fillRect(i,lineY-52,34,34);
    for(i=0;i<6;i++){g.fillStyle='#2a2d43';g.beginPath();g.arc(120+i*210,300+((i*97)%200),10,0,6.3);g.fill();}
    // neon skyline
    g.fillStyle='#2a2650';g.fillRect(0,0,W,128);
    var bx=0,nc=['#ff5ea8','#35e0d0','#ffd166','#8c7bff'];
    while(bx<W){var bw=70+Math.floor(rng()*60),bh=46+Math.floor(rng()*60);
      box(bx,126-bh,bw,bh,4,['#3b3770','#463f85','#322e5c'][Math.floor(rng()*3)],3);
      for(j=0;j<bw-20;j+=22)for(var kk=0;kk<bh-20;kk+=20)if(rng()<.55){g.fillStyle=nc[Math.floor(rng()*4)];g.fillRect(bx+10+j,126-bh+10+kk,12,10);}
      bx+=bw+4;}
    box(-10,120,W+20,16,3,'#8f8bc4');
    // plaza (safe zone)
    g.fillStyle='#7d79ad';g.fillRect(0,lineY,W,H-lineY);
    g.strokeStyle='rgba(29,27,46,.35)';g.lineWidth=2;
    for(i=0;i<W;i+=70){g.beginPath();g.moveTo(i,lineY);g.lineTo(i,H);g.stroke();}
    for(j=lineY;j<H;j+=48){g.beginPath();g.moveTo(0,j);g.lineTo(W,j);g.stroke();}
    for(i=0;i<3;i++){var lx=W*(0.2+i*0.3);g.fillStyle='#ffe9a0';g.beginPath();g.arc(lx,H-26,14,0,6.3);g.fill();g.lineWidth=3;g.strokeStyle=INK;g.stroke();}
  }
  return c;
}

/* ---------- characters ---------- */
/* ---- the Taya's face: expressions drawn inside the head (units are sprite units) ---- */
function faceExpr(hx,hy,m,skin,glow){
  var t=anim,ink='#10131c',e1=hx+0.9,e2=hx+3.3,ey=hy+0.6,mx=hx+2.3,my=hy+3.5,i,k;
  var blink=(t%3.4)<0.12&&m!=='dizzy'&&m!=='laugh';
  var pup=glow?'#2fd45a':ink;
  ctx.lineCap='round';ctx.lineJoin='round';
  function ln(x1,y1,x2,y2,w){ctx.strokeStyle=ink;ctx.lineWidth=w||0.55;ctx.beginPath();ctx.moveTo(x1,y1);ctx.lineTo(x2,y2);ctx.stroke();}
  function ar(x,y,r,a0,a1,w){ctx.strokeStyle=ink;ctx.lineWidth=w||0.5;ctx.beginPath();ctx.arc(x,y,r,a0,a1);ctx.stroke();}
  function eye(x,r,px,py){
    if(blink){ln(x-r,ey,x+r,ey,0.5);return;}
    ctx.fillStyle='#fff';ctx.beginPath();ctx.ellipse(x,ey,r,r*1.08,0,0,6.2832);ctx.fill();
    ctx.strokeStyle='rgba(30,15,6,.8)';ctx.lineWidth=0.25;ctx.stroke();
    ctx.fillStyle=pup;ctx.beginPath();ctx.arc(x+(px===undefined?0.3:px),ey+(py||0),r*0.55,0,6.2832);ctx.fill();
    ctx.fillStyle='#fff';ctx.beginPath();ctx.arc(x+(px||0.3)-0.2,ey-0.25+(py||0),r*0.18,0,6.2832);ctx.fill();
  }
  function mouthOpen(w,h,teeth,tongue){
    ctx.fillStyle='#4a0f12';ctx.beginPath();ctx.ellipse(mx,my,w,h,0,0,6.2832);ctx.fill();
    ctx.strokeStyle=ink;ctx.lineWidth=0.35;ctx.stroke();
    if(teeth){ctx.fillStyle='#fff';ctx.fillRect(mx-w*0.75,my-h*0.9,w*1.5,Math.min(h*0.7,0.9));}
    if(tongue){ctx.fillStyle='#ff7a8a';ctx.beginPath();ctx.ellipse(mx,my+h*0.45,w*0.55,h*0.4,0,0,6.2832);ctx.fill();}
  }
  var sh=(m==='furious'||m==='scared'||m==='panic')?Math.sin(t*40)*0.25:0;
  ctx.save();ctx.translate(sh,0);
  if(m==='furious'){ctx.fillStyle='rgba(255,40,20,.38)';ctx.beginPath();ctx.arc(hx,hy,5.5,0,6.2832);ctx.fill();}
  if(m==='smug'){
    eye(e1,1.0);eye(e2,1.0);
    ctx.fillStyle=skin;ctx.fillRect(e1-1.2,ey-1.3,2.4,1.25);ctx.fillRect(e2-1.2,ey-1.3,2.4,1.25);
    ln(e1-1.1,ey-0.1,e1+1.1,ey-0.1,0.5);ln(e2-1.1,ey-0.1,e2+1.1,ey-0.1,0.5);
    ln(e1-1.1,hy-1.1,e1+1.1,hy-1.3,0.5);ln(e2-1.1,hy-1.7,e2+1.1,hy-1.0,0.5);
    ctx.strokeStyle=ink;ctx.lineWidth=0.5;ctx.beginPath();ctx.moveTo(mx-1.2,my);ctx.quadraticCurveTo(mx+0.4,my+0.9,mx+1.6,my-0.5);ctx.stroke();
  }else if(m==='shock'){
    eye(e1,1.5,0.2);eye(e2,1.5,0.2);
    ar(e1,hy-1.7,1.2,1.15*Math.PI,1.85*Math.PI,0.5);ar(e2,hy-1.7,1.2,1.15*Math.PI,1.85*Math.PI,0.5);
    mouthOpen(0.8,1.1+Math.sin(t*20)*0.15,false,false);
  }else if(m==='angry'||m==='furious'){
    var f=m==='furious';
    eye(e1,0.95,0.5);eye(e2,0.95,0.5);
    ln(e1-1.3,hy-1.9,e1+1.2,hy-0.6,f?0.9:0.7);ln(e2-1.2,hy-0.6,e2+1.3,hy-1.9,f?0.9:0.7);
    var a=0.5+0.5*Math.abs(Math.sin(t*(f?18:12)));
    mouthOpen(f?1.5:1.25,(f?0.8:0.5)+a*(f?1.2:0.8),true,false);
    if(f){ln(hx-3.8,hy-3.2,hx-2.6,hy-3.9,0.5);ln(hx-3.8,hy-3.9,hx-2.6,hy-3.2,0.5);}
  }else if(m==='sad'){
    eye(e1,1.2,0.1,0.25);eye(e2,1.2,0.1,0.25);
    ln(e1-1.2,hy-0.8,e1+1.2,hy-2.0,0.5);ln(e2-1.2,hy-2.0,e2+1.2,hy-0.8,0.5);
    ar(mx,my+1.7,1.4,1.2*Math.PI,1.8*Math.PI,0.55);
    for(i=0;i<2;i++){k=(t*1.7+i*0.5)%1;ctx.fillStyle='rgba(110,200,255,'+(1-k*0.7)+')';ctx.beginPath();ctx.ellipse((i?e2:e1)+0.3,ey+1.3+k*3.2,0.45,0.65,0,0,6.2832);ctx.fill();}
  }else if(m==='panic'||m==='scared'){
    eye(e1,1.3,0.2+Math.sin(t*30)*0.3);eye(e2,1.3,0.2+Math.sin(t*30)*0.3);
    ln(e1-1.2,hy-1.3,e1+1.2,hy-2.3,0.5);ln(e2-1.2,hy-2.3,e2+1.2,hy-1.3,0.5);
    ctx.strokeStyle=ink;ctx.lineWidth=0.5;ctx.beginPath();ctx.moveTo(mx-1.5,my);for(i=1;i<=6;i++)ctx.lineTo(mx-1.5+i*0.5,my+(i%2?0.55:-0.1));ctx.stroke();
    k=(t*1.3)%1;ctx.fillStyle='rgba(140,215,255,'+(1-k)+')';ctx.beginPath();ctx.ellipse(hx+5.2,hy-3+k*4,0.5,0.8,0,0,6.2832);ctx.fill();
  }else if(m==='laugh'){
    ar(e1,ey+0.6,0.9,1.1*Math.PI,1.9*Math.PI,0.6);ar(e2,ey+0.6,0.9,1.1*Math.PI,1.9*Math.PI,0.6);
    ctx.fillStyle='#4a0f12';ctx.beginPath();ctx.arc(mx,my-0.8,1.9,0,Math.PI);ctx.closePath();ctx.fill();
    ctx.strokeStyle=ink;ctx.lineWidth=0.35;ctx.stroke();
    ctx.fillStyle='#fff';ctx.fillRect(mx-1.5,my-0.85,3,0.55);
    ctx.fillStyle='#ff7a8a';ctx.beginPath();ctx.ellipse(mx,my+0.6,0.9,0.5,0,0,6.2832);ctx.fill();
  }else if(m==='dizzy'){
    for(i=0;i<2;i++){var ex=i?e2:e1;ctx.strokeStyle=ink;ctx.lineWidth=0.4;ctx.beginPath();for(k=0;k<=28;k++){var ra=k/28*1.1,an=k*0.5+t*9;var px=ex+Math.cos(an)*ra,py=ey+Math.sin(an)*ra;if(k===0)ctx.moveTo(px,py);else ctx.lineTo(px,py);}ctx.stroke();}
    ctx.strokeStyle=ink;ctx.lineWidth=0.5;ctx.beginPath();ctx.moveTo(mx-1.4,my);for(i=1;i<=6;i++)ctx.lineTo(mx-1.4+i*0.47,my+(i%2?0.5:-0.2));ctx.stroke();
    ln(e1-1.1,hy-1.4,e1+1.1,hy-1.6,0.45);ln(e2-1.1,hy-1.6,e2+1.1,hy-1.4,0.45);
  }else{ // focus: tongue out while setting the can back up
    eye(e1,1.0,0.5,0.3);eye(e2,1.0,0.5,0.3);
    ln(e1-1.1,hy-1.3,e1+1.1,hy-1.0,0.5);ln(e2-1.1,hy-1.0,e2+1.1,hy-1.3,0.5);
    ln(mx-1.0,my,mx+1.2,my,0.5);
    ctx.fillStyle='#ff7a8a';ctx.beginPath();ctx.ellipse(mx+0.9,my+0.65,0.6,0.8,0,0,6.2832);ctx.fill();
    ctx.strokeStyle=ink;ctx.lineWidth=0.25;ctx.stroke();
  }
  ctx.restore();
}
/* subtle texture + lighting so painted backgrounds feel less flat */
function texturize(c){
  var g=c.getContext('2d'),r=mulberry(c.width*7+c.height),i,w=c.width,h=c.height;
  for(i=0;i<w*h/90;i++){g.fillStyle=r()<.5?'rgba(0,0,0,.06)':'rgba(255,255,255,.05)';g.fillRect(r()*w,r()*h,1+r()*2,1+r()*2);}
  var gr=g.createLinearGradient(0,0,0,h);gr.addColorStop(0,'rgba(255,255,255,.10)');gr.addColorStop(.5,'rgba(0,0,0,0)');gr.addColorStop(1,'rgba(0,0,0,.22)');
  g.fillStyle=gr;g.fillRect(0,0,w,h);
  return c;
}
var LK=null;
function lookOf(o){if(!LK){LK={};for(var id in CHARS)LK[CHARS[id].shirt]=id;}return LK[o.shirt]||'';}
function person(x,y,o){
  var u=o.scale||2.4,f=o.face||1,st=o.step?1:0,b=o.bend||0;
  ctx.save();ctx.translate(Math.round(x),Math.round(y));
  shadowEll(0,3,24,8,.3);
  if(o.ring){ctx.strokeStyle=o.ring;ctx.lineWidth=4;ctx.beginPath();ctx.ellipse(0,4,28,10,0,0,6.2832);ctx.stroke();}
  ctx.scale(f*u,u);
  var lw=1.25,sw=st?2:-2;
  RR(-7.2+b*.6,-21+b,3,9,1.5,o.skin,lw);
  RR(-4+sw,-8,3.4,8.5,1.5,o.skin,lw);RR(0.8-sw,-8,3.4,8.5,1.5,o.skin,lw);
  RR(-4.6+sw,-1.2,4.8,2.4,1.1,o.shoe||'#ffffff',lw);RR(0.2-sw,-1.2,4.8,2.4,1.1,o.shoe||'#ffffff',lw);
  RR(-5.4,-13.5,10.8,6.5,2.2,o.pants,lw);
  var lk=lookOf(o),hv=lk==='SANTIAGO'?1.3:0;
  if(lk==='MARIA'){ctx.fillStyle='#ffffff';ctx.fillRect(-4+sw,-4.2,3.4,1.1);ctx.fillRect(0.8-sw,-4.2,3.4,1.1);ctx.fillStyle='#ff7fbf';ctx.fillRect(-4+sw,-3.1,3.4,.7);ctx.fillRect(0.8-sw,-3.1,3.4,.7);}
  if(lk==='JUAN'){ctx.fillStyle='#e0262e';ctx.fillRect(-4+sw,-5,3.4,1.3);ctx.fillRect(0.8-sw,-5,3.4,1.3);}
  RR(-5.6-hv+b*.5,-22.5+b,11.2+hv*2,10.5,3.2,o.shirt,lw);
  ctx.fillStyle='rgba(10,12,30,.2)';ctx.fillRect(2.6+hv+b*.5,-21.4+b,2.2,8.6);ctx.fillRect(-5+b*.5,-14.6+b,9.8,1.6);
  ctx.fillStyle='rgba(10,12,30,.2)';ctx.fillRect(1.6,-12.4,3.6,4.6);
  if(o.stripe){ctx.fillStyle=o.stripe;ctx.fillRect(-5+b*.5,-18+b,10,2);}
  if(lk==='SANTIAGO'){ctx.fillStyle='#3a2210';ctx.fillRect(-5.4,-14.4,10.8,1.7);ctx.fillStyle='#ffd23f';ctx.fillRect(-0.8,-14.4,2.2,1.7);}
  if(lk==='BERTO'){ctx.strokeStyle='#6a4524';ctx.lineWidth=1.5;ctx.beginPath();ctx.moveTo(-4.4,-21.6+b);ctx.lineTo(4.4,-13.4+b);ctx.stroke();RR(2.4,-15.6+b,4.6,4.4,1,'#8a5a2a',lw*.8);ctx.fillStyle='#ffd23f';ctx.fillRect(4,-14.2+b,1.4,1);}
  if(o.up)RR(3.4+b*.4,-32,3.1,12,1.6,o.skin,lw);
  else RR(4.2+b*.6,-21.5+b,3.1,9.2,1.6,o.skin,lw);
  var hx=0.8+b*1.4,hy=-28+b*1.5;
  if(lk==='MARIA'){var sw3=Math.sin(anim*8)*1.2;RR(hx-9.4+sw3,hy-2,4.6,8,2.2,o.hair,lw);circ(hx-5.6,hy-1.6,2.2,'#ff7fbf',lw*.7);}
  if(lk==='JUAN'){var fl=Math.sin(anim*10)*1;RR(hx-10.4+fl,hy-1.2,5,1.8,.8,'#e0262e',lw*.7);RR(hx-9.4-fl,hy+.8,4.4,1.6,.8,'#e0262e',lw*.7);}
  circ(hx,hy,5.6,o.skin,lw);
  ctx.fillStyle='rgba(10,12,30,.14)';ctx.beginPath();ctx.arc(hx,hy,5.4,-1.1,1.2);ctx.lineTo(hx,hy);ctx.fill();
  var ha=o.expr?0.13:0.02;
  ctx.beginPath();ctx.arc(hx,hy,5.8,Math.PI*(1+ha),Math.PI*(2-ha));ctx.closePath();paint(o.hair,lw);
  if(o.band){RR(hx-5.8,hy-2.6,11.6,2.4,1,o.band,lw*.8);}
  if(o.expr)faceExpr(hx,hy,o.expr,o.skin,o.glow);
  else{
    ctx.fillStyle='#ffffff';ctx.beginPath();ctx.arc(hx+2.3,hy+0.5,1.55,0,6.3);ctx.fill();
    ctx.fillStyle=INK;ctx.beginPath();ctx.arc(hx+2.8,hy+0.6,0.95,0,6.3);ctx.fill();
    ctx.fillStyle='#fff';ctx.fillRect(hx+2.9,hy+0.1,.45,.45);
    ctx.strokeStyle=INK;ctx.lineWidth=.5;ctx.beginPath();ctx.moveTo(hx+0.8,hy-1.6);ctx.lineTo(hx+3.8,hy-1.9);ctx.stroke();
    ctx.beginPath();ctx.arc(hx+2.6,hy+2.7,1.1,0.2,2.9);ctx.stroke();
    ctx.fillStyle='rgba(255,110,120,.35)';ctx.beginPath();ctx.arc(hx+0.6,hy+2.4,1,0,6.3);ctx.fill();
    if(o.glow){circ(hx+2.6,hy+0.6,1.7,'#7dff7a',0);ctx.fillStyle='#7dff7a';ctx.fill();}
    if(lk==='BERTO'){ctx.strokeStyle='#14121c';ctx.lineWidth=.55;ctx.beginPath();ctx.arc(hx+2.4,hy+.5,2.1,0,6.3);ctx.stroke();ctx.beginPath();ctx.moveTo(hx+.3,hy+.4);ctx.lineTo(hx-1,hy+.2);ctx.stroke();}
    if(lk==='SANTIAGO'){ctx.strokeStyle='#14100c';ctx.lineWidth=.9;ctx.beginPath();ctx.moveTo(hx+1,hy+3.6);ctx.quadraticCurveTo(hx+3,hy+4.4,hx+4.8,hy+3.4);ctx.stroke();}
  }
  if(lk==='JUAN'){ctx.beginPath();ctx.moveTo(hx-4.2,hy-4);ctx.lineTo(hx-3.2,hy-8.2);ctx.lineTo(hx-1.6,hy-5);ctx.lineTo(hx-.4,hy-9);ctx.lineTo(hx+1,hy-5.2);ctx.lineTo(hx+2.6,hy-8.4);ctx.lineTo(hx+3.6,hy-4.2);ctx.closePath();paint(o.hair,lw);RR(hx-5.8,hy-3.2,11.6,2.2,.8,'#e0262e',lw*.7);}
  if(lk==='MARIA'){RR(hx-5.8,hy-3.4,11.6,1.8,.8,'#ffffff',lw*.7);}
  if(o.hat==='salakot'){ctx.beginPath();ctx.moveTo(hx-9,hy-3.5);ctx.lineTo(hx,hy-12.5);ctx.lineTo(hx+9,hy-3.5);ctx.closePath();paint('#e6c27a',lw);}
  ctx.restore();
}
/* pamato in the equipped skin: orange tsinelas, purple slides, flower clogs, golden tsinelas */
function slipperShape(rot,ghost,sc,sk){
  sk=sk||skin();
  ctx.save();ctx.rotate(rot);ctx.scale(sc,sc);
  if(ghost){ctx.shadowColor='#b9d4ff';ctx.shadowBlur=14;}
  else if(sk===SKINS.GOLD){ctx.shadowColor='#ffd23f';ctx.shadowBlur=10;}
  RR(-5,-11,10,22,5,ghost?'rgba(235,243,255,.9)':sk.c1,1.3);
  ctx.shadowBlur=0;
  RR(-3.3,-8.5,6.6,17,3.3,ghost?'rgba(255,255,255,.9)':sk.c2,0);
  if(!ghost&&sk.band){ctx.fillStyle=sk.strap;ctx.fillRect(-5,-3.5,10,6);ctx.strokeStyle=INK;ctx.lineWidth=1.1;ctx.strokeRect(-5,-3.5,10,6);}
  else{
    ctx.strokeStyle=ghost?'#7f95c9':sk.strap;ctx.lineWidth=2.2;
    ctx.beginPath();ctx.moveTo(-3.6,-2);ctx.lineTo(0,3.5);ctx.lineTo(3.6,-2);ctx.stroke();
    ctx.beginPath();ctx.moveTo(0,3.5);ctx.lineTo(0,7);ctx.stroke();
  }
  if(!ghost&&sk.flowers){
    var fl=[[-1.5,-7],[1.8,6.5],[2,-5.5]];
    for(var i=0;i<fl.length;i++){ctx.fillStyle=sk.strap;ctx.beginPath();ctx.arc(fl[i][0],fl[i][1],1.3,0,6.3);ctx.fill();ctx.fillStyle='#fff36b';ctx.beginPath();ctx.arc(fl[i][0],fl[i][1],.5,0,6.3);ctx.fill();}
  }
  if(!ghost&&sk.baybayin){ctx.strokeStyle=sk.strap;ctx.lineWidth=1.3;ctx.beginPath();ctx.arc(-1.4,-5.5,1.5,0.3,5.5);ctx.moveTo(1.5,-1);ctx.lineTo(1.5,3);ctx.moveTo(-2.6,5);ctx.arc(-1.5,6.2,1.2,3.5,0.5);ctx.stroke();}
  if(!ghost&&sk.jeep){ctx.fillStyle='#e0262e';ctx.fillRect(-5,-8,10,3);ctx.fillStyle='#ffd23f';ctx.fillRect(-5,-3.5,10,3);ctx.fillStyle='#2a62c9';ctx.fillRect(-5,1,10,3);}
  if(!ghost&&sk===SKINS.GOLD){ctx.fillStyle='rgba(255,255,255,.7)';ctx.fillRect(-3.5,-8,1.4,7);}
  ctx.restore();
}
function drawSlipper(s){
  var x=Math.round(s.x),y=Math.round(s.y);
  if(s.state==='fly'||s.state==='rico'){
    shadowEll(x,y+4,16,6,.22);
    ctx.save();ctx.translate(x,Math.round(y-s.z));slipperShape(s.rot,s.ghost,2.1);ctx.restore();
  }else{
    var gh=s.state==='ghost'||s.ghost;
    shadowEll(x,y+6,18,6,.25);
    ctx.save();ctx.translate(x,y-4);
    if(s.state==='ghost')ctx.globalAlpha=.55+.4*Math.sin(anim*12);
    slipperShape(0.6,gh,2.0);ctx.restore();
    if(s.state==='ground'){var bob=Math.sin(anim*5+x)*3;ctx.beginPath();ctx.moveTo(x-9,y-46+bob);ctx.lineTo(x+9,y-46+bob);ctx.lineTo(x,y-32+bob);ctx.closePath();paint('#fff36b',2.5);}
  }
}
function drawCoin(c){
  var sx=Math.abs(Math.cos(anim*3+c.ph)),y=c.y-26+Math.sin(anim*4+c.ph)*4;
  if(c.t<4&&Math.floor(anim*8)%2)return;
  shadowEll(c.x,c.y+4,16,5,.25);
  ctx.save();ctx.translate(c.x,y);ctx.scale(Math.max(.2,sx),1);coinIcon(0,0,17);ctx.restore();
}
function drawPuddle(p){
  ctx.save();
  ctx.fillStyle='rgba(60,150,220,.62)';ctx.beginPath();ctx.ellipse(p.x,p.y,p.rx,p.ry,0,0,6.3);ctx.fill();
  ctx.lineWidth=3;ctx.strokeStyle='rgba(29,27,46,.55)';ctx.stroke();
  ctx.strokeStyle='rgba(220,245,255,.75)';ctx.lineWidth=2.5;
  var r=(anim*14)%20;
  ctx.beginPath();ctx.ellipse(p.x-p.rx*.2,p.y,p.rx*.35+r*.5,p.ry*.35+r*.2,0,0,6.3);ctx.stroke();
  ctx.fillStyle='rgba(255,255,255,.4)';ctx.beginPath();ctx.ellipse(p.x+p.rx*.35,p.y-p.ry*.3,p.rx*.2,p.ry*.12,0,0,6.3);ctx.fill();
  ctx.restore();
}
var PED_COLS=['#d8452e','#7ddc6b','#5fb6ff','#ffb347','#c58bff'];
function drawObstacle(o){
  var x=o.x-o.w/2,y=o.y-o.h/2;
  if(o.type==='ped'){
    person(o.x,o.y+16,{face:o.vx>0?1:-1,step:Math.floor(anim*8+o.sh*5)%2===0,skin:'#e8b48a',hair:'#2a1c14',shirt:PED_COLS[o.sh%5],pants:'#4a4a6a',scale:2.3,shoe:'#1d2130'});
    return;
  }
  shadowEll(o.x,o.y+o.h/2,o.w/2,6,.25);
  if(o.type==='desk'){RR(x,y,o.w,o.h,6,'#c98b4a');RR(x+6,y+6,o.w-12,o.h-16,3,'#e7ac66',2);RR(x+10,y+o.h-9,10,9,2,'#7a4a22',2);RR(x+o.w-20,y+o.h-9,10,9,2,'#7a4a22',2);}
  else if(o.type==='bench'){RR(x,y,o.w,o.h,6,'#9b6a3a');for(var i=0;i<4;i++)RR(x+6+i*(o.w-12)/4,y+4,(o.w-12)/4-4,o.h-8,2,'#d39a5b',2);}
  else if(o.type==='bike'){
    circ(x+22,y+o.h-14,14,'rgba(255,255,255,.2)',4);circ(x+o.w-22,y+o.h-14,14,'rgba(255,255,255,.2)',4);
    ctx.strokeStyle=INK;ctx.lineWidth=7;ctx.beginPath();ctx.moveTo(x+22,y+o.h-14);ctx.lineTo(x+o.w/2,y+8);ctx.lineTo(x+o.w-22,y+o.h-14);ctx.moveTo(x+o.w/2,y+8);ctx.lineTo(x+o.w/2-8,y+o.h-14);ctx.lineTo(x+22,y+o.h-14);ctx.stroke();
    ctx.strokeStyle='#ff4d5e';ctx.lineWidth=3.5;ctx.stroke();
    ctx.strokeStyle=INK;ctx.lineWidth=5;ctx.beginPath();ctx.moveTo(x+o.w-22,y+o.h-14);ctx.lineTo(x+o.w-26,y+2);ctx.lineTo(x+o.w-14,y);ctx.stroke();
    RR(x+o.w/2-18,y+4,22,6,3,'#1d2130',2);
  }
  else if(o.type==='trash'){RR(x+3,y+8,o.w-6,o.h-8,6,'#3cb371');RR(x,y,o.w,12,5,'#2e8b57');ctx.strokeStyle=INK;ctx.lineWidth=2.5;for(var q=1;q<4;q++){ctx.beginPath();ctx.moveTo(x+q*o.w/4,y+16);ctx.lineTo(x+q*o.w/4,y+o.h-6);ctx.stroke();}}
  else if(o.type==='barrel'){circ(o.x,o.y,o.w/2,'#2d7be0');circ(o.x,o.y,o.w/2-7,'#58a0ff',2);circ(o.x,o.y,5,'#1b4f9e',2);}
  else if(o.type==='crate'){RR(x,y,o.w,o.h,5,'#d9a05b');ctx.strokeStyle=INK;ctx.lineWidth=2.5;ctx.beginPath();ctx.moveTo(x+4,y+4);ctx.lineTo(x+o.w-4,y+o.h-4);ctx.moveTo(x+o.w-4,y+4);ctx.lineTo(x+4,y+o.h-4);ctx.stroke();}
  else if(o.type==='cart'){RR(x,y,o.w,o.h-6,6,'#a0a9bd');RR(x+6,y+5,o.w-12,o.h-18,3,'#cdd4e4',2);circ(x+16,y+o.h-4,6,'#1d2130',2);circ(x+o.w-16,y+o.h-4,6,'#1d2130',2);}
  else if(o.type==='trike'){
    var f=o.vx>0?1:-1;
    RR(x+2,y+6,o.w-4,o.h-12,12,'#ff4d5e');
    RR(x+(f>0?o.w-40:8),y+9,32,o.h-18,6,'#ffd23f',2.5);
    RR(x+(f>0?o.w-8:2),y+12,6,o.h-24,3,'#fff6c9',2);
    circ(x+16,y+o.h-4,8,'#1d2130',2);circ(x+o.w-16,y+o.h-4,8,'#1d2130',2);
  }
}
/* =========================================================================
   v5 art: biome backgrounds 3-5, biome obstacles + ground patches, biome-adaptive Lata and Taya
   ========================================================================= */
function buildBGnew(env,lineY,W){
  var c=document.createElement('canvas');c.width=W;c.height=H;
  var g=c.getContext('2d'),rng=mulberry(env*977+5),i,j,gr;
  g.lineJoin='round';g.lineCap='round';
  function box(x,y,w,h,r,fill,lw){g.beginPath();g.moveTo(x+r,y);g.arcTo(x+w,y,x+w,y+h,r);g.arcTo(x+w,y+h,x,y+h,r);g.arcTo(x,y+h,x,y,r);g.arcTo(x,y,x+w,y,r);g.closePath();g.fillStyle=fill;g.fill();g.lineWidth=lw||3;g.strokeStyle=INK;g.stroke();}
  if(env===3){                                   // BEACH
    gr=g.createLinearGradient(0,0,0,H);gr.addColorStop(0,'#f6e3ac');gr.addColorStop(1,'#e8c88a');g.fillStyle=gr;g.fillRect(0,0,W,H);
    g.fillStyle='#7ad8e8';g.fillRect(0,0,W,76);g.fillStyle='#3fb6d6';g.fillRect(0,52,W,40);
    g.strokeStyle='rgba(255,255,255,.8)';g.lineWidth=3;
    for(i=0;i<W;i+=34){g.beginPath();g.moveTo(i,70);g.quadraticCurveTo(i+9,60,i+18,70);g.quadraticCurveTo(i+27,80,i+34,70);g.stroke();}
    g.fillStyle='#fff6d0';g.beginPath();g.moveTo(0,96);for(i=0;i<=W;i+=24)g.lineTo(i,92+Math.sin(i*.09)*6);g.lineTo(W,130);g.lineTo(0,130);g.closePath();g.fill();g.lineWidth=3;g.strokeStyle=INK;g.stroke();
    for(i=0;i<4;i++){var px=60+i*(W/4)+rng()*40;g.strokeStyle=INK;g.lineWidth=9;g.beginPath();g.moveTo(px,128);g.quadraticCurveTo(px+14,80,px+6,30);g.stroke();g.strokeStyle='#8a5a2b';g.lineWidth=5;g.stroke();
      g.fillStyle='#3cb371';for(j=0;j<5;j++){g.beginPath();g.ellipse(px+6+Math.cos(j*1.3)*20,32+Math.sin(j*1.3)*8,22,6,j*1.3,0,6.3);g.fill();g.lineWidth=2;g.stroke();}}
    for(i=0;i<60;i++){g.fillStyle=rng()<.5?'rgba(255,255,255,.35)':'rgba(150,110,50,.18)';g.beginPath();g.ellipse(rng()*W,150+rng()*540,6+rng()*26,2+rng()*6,0,0,6.3);g.fill();}
    for(i=0;i<9;i++){g.fillStyle=['#ff9ec7','#fff','#ffb36b'][i%3];g.beginPath();g.arc(rng()*W,170+rng()*400,4+rng()*3,0,6.3);g.fill();g.lineWidth=2;g.strokeStyle=INK;g.stroke();}
    g.fillStyle='#b98550';g.fillRect(0,lineY,W,H-lineY);g.strokeStyle='rgba(29,27,46,.45)';g.lineWidth=2;
    for(i=0;i<W;i+=64){g.beginPath();g.moveTo(i,lineY);g.lineTo(i,H);g.stroke();}
    for(j=lineY+24;j<H;j+=24){g.beginPath();g.moveTo(0,j);g.lineTo(W,j);g.stroke();}
  }else if(env===4){                             // FOREST
    gr=g.createLinearGradient(0,0,0,H);gr.addColorStop(0,'#8fc46a');gr.addColorStop(1,'#4f7f3a');g.fillStyle=gr;g.fillRect(0,0,W,H);
    g.fillStyle='#2c5a2f';g.fillRect(0,0,W,128);
    for(i=0;i<W+40;i+=58){var th=70+rng()*40;g.fillStyle='#6a4524';g.fillRect(i,128-th,22,th);g.strokeStyle=INK;g.lineWidth=3;g.strokeRect(i,128-th,22,th);
      g.fillStyle=['#3d8f45','#2f7a3a','#4aa352'][Math.floor(rng()*3)];g.beginPath();g.arc(i+11,128-th,34,0,6.3);g.fill();g.stroke();}
    g.fillStyle='rgba(200,170,110,.35)';g.beginPath();g.ellipse(W/2,380,W*.38,150,0,0,6.3);g.fill();
    for(i=0;i<70;i++){g.fillStyle=rng()<.5?'rgba(255,255,255,.07)':'rgba(20,50,20,.1)';g.beginPath();g.ellipse(rng()*W,140+rng()*560,10+rng()*40,4+rng()*12,0,0,6.3);g.fill();}
    for(i=0;i<40;i++){var gx=rng()*W,gy=150+rng()*430;g.strokeStyle='#2e6a32';g.lineWidth=3;g.beginPath();g.moveTo(gx,gy);g.lineTo(gx-4,gy-12);g.moveTo(gx,gy);g.lineTo(gx+5,gy-14);g.stroke();}
    for(i=0;i<6;i++){var mx=60+rng()*(W-120),my=180+rng()*380;g.fillStyle='#fff';g.fillRect(mx-2,my,4,9);g.fillStyle='#e8453a';g.beginPath();g.arc(mx,my,8,Math.PI,0);g.fill();g.lineWidth=2;g.strokeStyle=INK;g.stroke();}
    g.fillStyle='#7f9a74';g.fillRect(0,lineY,W,H-lineY);g.strokeStyle='rgba(29,27,46,.4)';g.lineWidth=2;
    for(i=0;i<W;i+=80){g.beginPath();g.moveTo(i,lineY);g.lineTo(i,H);g.stroke();}
    for(j=lineY+30;j<H;j+=30){g.beginPath();g.moveTo(0,j);g.lineTo(W,j);g.stroke();}
    for(i=0;i<W;i+=60){g.fillStyle='#3f9f4f';g.beginPath();g.arc(i+30,H+4,30,0,6.3);g.fill();g.lineWidth=3;g.strokeStyle=INK;g.stroke();}
  }else{                                         // HAUNTED HOUSE
    gr=g.createLinearGradient(0,0,0,H);gr.addColorStop(0,'#1a1030');gr.addColorStop(1,'#35235a');g.fillStyle=gr;g.fillRect(0,0,W,H);
    for(i=0;i<28;i++)for(j=0;j<14;j++)if((i+j)%2===0){g.fillStyle='rgba(255,255,255,.035)';g.fillRect(i*(W/28),140+j*42,W/28,42);}
    g.fillStyle='#fff3c0';g.beginPath();g.arc(W-90,56,30,0,6.3);g.fill();g.lineWidth=3;g.strokeStyle=INK;g.stroke();
    g.fillStyle='#120a22';g.beginPath();g.moveTo(40,128);g.lineTo(40,60);g.lineTo(90,60);g.lineTo(90,28);g.lineTo(110,6);g.lineTo(130,28);g.lineTo(130,60);g.lineTo(W/2,60);g.lineTo(W/2,34);g.lineTo(W/2+22,10);g.lineTo(W/2+44,34);g.lineTo(W/2+44,60);g.lineTo(W-150,60);g.lineTo(W-150,128);g.closePath();g.fill();g.stroke();
    for(i=0;i<9;i++){g.fillStyle=rng()<.6?'#ffd966':'#3b2a62';g.fillRect(60+i*((W-260)/9),78+(i%2)*14,18,24);}
    g.strokeStyle='rgba(255,255,255,.45)';g.lineWidth=2;
    for(i=1;i<5;i++){g.beginPath();g.moveTo(0,140);g.quadraticCurveTo(i*26,140+i*26,i*52,140);g.moveTo(W,140);g.quadraticCurveTo(W-i*26,140+i*26,W-i*52,140);g.stroke();}
    for(i=0;i<40;i++){g.fillStyle='rgba(150,100,230,.08)';g.beginPath();g.ellipse(rng()*W,150+rng()*540,14+rng()*50,5+rng()*14,0,0,6.3);g.fill();}
    g.fillStyle='#5a2a6a';g.fillRect(0,lineY,W,H-lineY);g.strokeStyle='#e8c25a';g.lineWidth=4;g.strokeRect(10,lineY+10,W-20,H-lineY-20);
    g.strokeStyle='rgba(0,0,0,.3)';g.lineWidth=2;for(i=0;i<W;i+=50){g.beginPath();g.moveTo(i,lineY);g.lineTo(i,H);g.stroke();}
    for(i=0;i<3;i++){var cxn=W*(0.2+i*0.3);g.fillStyle='#fff3c0';g.fillRect(cxn-5,H-46,10,26);g.fillStyle='#ffb02e';g.beginPath();g.ellipse(cxn,H-52,5,9,0,0,6.3);g.fill();g.strokeStyle=INK;g.lineWidth=2;g.stroke();}
  }
  return c;
}

/* ---------- biome obstacles ---------- */
function drawObstNew(o){
  var x=o.x-o.w/2,y=o.y-o.h/2,t=o.type,i;
  shadowEll(o.x,o.y+o.h/2,o.w/2,6,.25);
  if(t==='umbrella'){
    RR(x+8,y+o.h-16,o.w-16,16,3,'#fff6c9',2.5);
    ctx.strokeStyle=INK;ctx.lineWidth=5;ctx.beginPath();ctx.moveTo(o.x,y+o.h-8);ctx.lineTo(o.x,y-6);ctx.stroke();
    ctx.beginPath();ctx.moveTo(x,y+10);ctx.quadraticCurveTo(o.x,y-30,x+o.w,y+10);ctx.closePath();paint('#ff4d5e',3);
    ctx.fillStyle='#fff';for(i=1;i<4;i++){ctx.beginPath();ctx.moveTo(x+i*o.w/4,y+9-(i%2?0:0));ctx.quadraticCurveTo(x+i*o.w/4,y-8,o.x,y-12);ctx.lineTo(o.x,y+10);ctx.closePath();if(i%2)ctx.fill();}
  }else if(t==='castle'){
    RR(x+4,y+16,o.w-8,o.h-16,4,'#e0b35e');RR(x,y+6,14,20,2,'#e0b35e');RR(x+o.w-14,y+6,14,20,2,'#e0b35e');RR(x+o.w/2-9,y,18,24,2,'#d9a548');
    ctx.strokeStyle=INK;ctx.lineWidth=2;ctx.beginPath();ctx.moveTo(o.x,y);ctx.lineTo(o.x,y-12);ctx.stroke();ctx.fillStyle='#ff4d5e';ctx.fillRect(o.x,y-12,9,6);
  }else if(t==='banca'){
    ctx.beginPath();ctx.moveTo(x,y+10);ctx.quadraticCurveTo(o.x,y+o.h+6,x+o.w,y+10);ctx.lineTo(x+o.w-8,y+2);ctx.lineTo(x+8,y+2);ctx.closePath();paint('#2a9d95',3);
    ctx.fillStyle='#ffd23f';ctx.fillRect(x+10,y+8,o.w-20,5);
    ctx.strokeStyle=INK;ctx.lineWidth=4;ctx.beginPath();ctx.moveTo(x+14,y+8);ctx.lineTo(x+14,y+o.h);ctx.moveTo(x+o.w-14,y+8);ctx.lineTo(x+o.w-14,y+o.h);ctx.moveTo(x+14,y+o.h);ctx.lineTo(x+o.w-14,y+o.h);ctx.stroke();
  }else if(t==='log'){
    RR(x,y+4,o.w,o.h-4,14,'#8a5a2b');ctx.strokeStyle=INK;ctx.lineWidth=2;for(i=1;i<5;i++){ctx.beginPath();ctx.moveTo(x+i*o.w/5,y+8);ctx.lineTo(x+i*o.w/5-4,y+o.h-4);ctx.stroke();}
    circ(x+o.w-8,o.y+2,o.h/2-2,'#d9a566',2.5);circ(x+o.w-8,o.y+2,o.h/4,'#b8803f',2);
  }else if(t==='stump'){
    RR(x+4,y+10,o.w-8,o.h-10,6,'#8a5a2b');ctx.beginPath();ctx.ellipse(o.x,y+12,o.w/2,10,0,0,6.3);paint('#d9a566',3);ctx.beginPath();ctx.ellipse(o.x,y+12,o.w/4,5,0,0,6.3);ctx.strokeStyle='#8a5a2b';ctx.lineWidth=2;ctx.stroke();
  }else if(t==='bush'){
    circ(x+16,y+o.h-18,18,'#3f9f4f');circ(o.x,y+16,22,'#4fb85a');circ(x+o.w-16,y+o.h-18,18,'#3f9f4f');circ(o.x+8,y+8,6,'#e8453a',2);circ(o.x-14,y+22,5,'#e8453a',2);
  }else if(t==='tomb'){
    ctx.beginPath();ctx.moveTo(x+2,y+o.h);ctx.lineTo(x+2,y+18);ctx.arc(o.x,y+18,o.w/2-2,Math.PI,0);ctx.lineTo(x+o.w-2,y+o.h);ctx.closePath();paint('#a7a8c4',3);
    ctx.strokeStyle=INK;ctx.lineWidth=3;ctx.beginPath();ctx.moveTo(o.x,y+10);ctx.lineTo(o.x,y+34);ctx.moveTo(o.x-8,y+18);ctx.lineTo(o.x+8,y+18);ctx.stroke();
  }else if(t==='coffin'){
    ctx.beginPath();ctx.moveTo(x+14,y);ctx.lineTo(x+o.w-14,y);ctx.lineTo(x+o.w,y+o.h/2);ctx.lineTo(x+o.w-14,y+o.h);ctx.lineTo(x+14,y+o.h);ctx.lineTo(x,y+o.h/2);ctx.closePath();paint('#6a4528',3);
    ctx.strokeStyle='#e8c25a';ctx.lineWidth=3;ctx.beginPath();ctx.moveTo(x+22,o.y);ctx.lineTo(x+o.w-22,o.y);ctx.stroke();
  }else if(t==='cauldron'){
    ctx.beginPath();ctx.ellipse(o.x,y+o.h-14,o.w/2,16,0,0,6.3);paint('#2a2a3a',3);RR(x+4,y+10,o.w-8,o.h-22,10,'#2a2a3a');
    ctx.beginPath();ctx.ellipse(o.x,y+12,o.w/2-4,8,0,0,6.3);paint('#6fe05a',2.5);
    for(i=0;i<3;i++)circ(o.x-10+i*10,y+6-((anim*14+i*7)%12),3,'#b6ff8a',1.5);
  }else if(t==='crab'){
    var w2=Math.sin(anim*14)*3;
    ctx.strokeStyle=INK;ctx.lineWidth=3;for(i=0;i<3;i++){ctx.beginPath();ctx.moveTo(o.x-10,o.y+4);ctx.lineTo(o.x-20,o.y+6+i*4+w2);ctx.moveTo(o.x+10,o.y+4);ctx.lineTo(o.x+20,o.y+6+i*4-w2);ctx.stroke();}
    ctx.beginPath();ctx.ellipse(o.x,o.y,15,10,0,0,6.3);paint('#ff5a3a',3);circ(o.x-18,o.y-9,6,'#ff5a3a',2.5);circ(o.x+18,o.y-9,6,'#ff5a3a',2.5);circ(o.x-4,o.y-11,2.5,'#fff',1.5);circ(o.x+4,o.y-11,2.5,'#fff',1.5);
  }else if(t==='carabao'){
    var f=o.vx>0?1:-1,st=Math.floor(anim*5)%2?3:-3;
    RR(o.x-30,o.y-12,60,26,12,'#6e7684');
    RR(o.x-26+st,o.y+10,8,12,3,'#59606d',2);RR(o.x-6-st,o.y+10,8,12,3,'#59606d',2);RR(o.x+10+st,o.y+10,8,12,3,'#59606d',2);RR(o.x+22-st,o.y+10,8,12,3,'#59606d',2);
    RR(o.x+f*30-12,o.y-16,24,22,8,'#6e7684');
    ctx.strokeStyle=INK;ctx.lineWidth=4;ctx.beginPath();ctx.arc(o.x+f*30,o.y-18,12,Math.PI*1.05,Math.PI*1.95);ctx.stroke();ctx.strokeStyle='#f2ead0';ctx.lineWidth=2;ctx.stroke();
    circ(o.x+f*36,o.y-8,2,'#fff',1.5);
  }else if(t==='ghost'){
    var b=Math.sin(anim*3+o.sh)*5;ctx.save();ctx.globalAlpha=.88;
    ctx.beginPath();ctx.moveTo(o.x-16,o.y+14+b);ctx.lineTo(o.x-16,o.y-4+b);ctx.arc(o.x,o.y-4+b,16,Math.PI,0);ctx.lineTo(o.x+16,o.y+14+b);
    ctx.lineTo(o.x+8,o.y+8+b);ctx.lineTo(o.x,o.y+14+b);ctx.lineTo(o.x-8,o.y+8+b);ctx.closePath();paint('#f2f4ff',3);
    circ(o.x-6,o.y-6+b,2.6,INK,0);ctx.fillStyle=INK;ctx.fill();circ(o.x+6,o.y-6+b,2.6,INK,0);ctx.fill();ctx.restore();
  }
}
function drawPatch(p){
  if(p.kind==='water'){drawPuddle(p);return;}
  ctx.save();
  if(p.kind==='lava'){
    ctx.fillStyle='#2a1c1e';ctx.beginPath();ctx.ellipse(p.x,p.y,p.rx,p.ry,0,0,6.3);ctx.fill();
    ctx.lineWidth=3;ctx.strokeStyle='rgba(255,'+(110+Math.floor(60*Math.sin(anim*3+p.ph)))+',30,.95)';ctx.stroke();
    ctx.fillStyle='rgba(255,106,42,'+(0.45+0.2*Math.sin(anim*3+p.ph))+')';ctx.beginPath();ctx.ellipse(p.x,p.y,p.rx*.62,p.ry*.55,0,0,6.3);ctx.fill();
    ctx.fillStyle='#ffd23f';ctx.fillRect(p.x-p.rx*.2,p.y-2,p.rx*.3,3);ctx.restore();return;
  }
  if(p.kind==='seaweed'){
    ctx.fillStyle='rgba(20,110,110,.5)';ctx.beginPath();ctx.ellipse(p.x,p.y,p.rx,p.ry,0,0,6.3);ctx.fill();ctx.lineWidth=3;ctx.strokeStyle='rgba(29,27,46,.5)';ctx.stroke();
    ctx.lineWidth=4;ctx.strokeStyle='#2fa05a';
    for(var i=0;i<9;i++){var fx=p.x-p.rx*.8+i*p.rx*.2,sw=Math.sin(anim*2+i*1.3)*6;ctx.beginPath();ctx.moveTo(fx,p.y+p.ry*.5);ctx.quadraticCurveTo(fx+sw,p.y-4,fx+sw*1.5,p.y-p.ry*.9);ctx.stroke();}
    ctx.strokeStyle='rgba(255,255,255,.55)';ctx.lineWidth=2;ctx.beginPath();ctx.ellipse(p.x+p.rx*.3,p.y-p.ry*.2,p.rx*.2,p.ry*.12,0,0,6.3);ctx.stroke();
  }else if(p.kind==='vine'){
    ctx.fillStyle='rgba(60,90,30,.45)';ctx.beginPath();ctx.ellipse(p.x,p.y,p.rx,p.ry,0,0,6.3);ctx.fill();ctx.lineWidth=3;ctx.strokeStyle='rgba(29,27,46,.5)';ctx.stroke();
    ctx.lineWidth=5;ctx.strokeStyle='#6a4524';
    for(var j=0;j<5;j++){ctx.beginPath();ctx.moveTo(p.x-p.rx*.9,p.y-p.ry*.6+j*p.ry*.3);ctx.bezierCurveTo(p.x-p.rx*.3,p.y+p.ry*(j%2?1:-1),p.x+p.rx*.3,p.y-p.ry*(j%2?1:-1),p.x+p.rx*.9,p.y+p.ry*.6-j*p.ry*.3);ctx.stroke();}
    ctx.strokeStyle='#4aa352';ctx.lineWidth=3;for(var k=0;k<4;k++){ctx.beginPath();ctx.arc(p.x-p.rx*.5+k*p.rx*.35,p.y+((k%2)?6:-6),6,0,6.3);ctx.stroke();}
  }else{
    var on=shadowOn(p);
    ctx.globalAlpha=on?(0.72+Math.sin(anim*30)*.12):0.14+0.08*Math.sin(anim*5+p.ph);
    ctx.fillStyle='#1a0a30';ctx.beginPath();ctx.ellipse(p.x,p.y,p.rx,p.ry,0,0,6.3);ctx.fill();
    ctx.lineWidth=3;ctx.strokeStyle='#b27cff';ctx.setLineDash([6,6]);ctx.stroke();ctx.setLineDash([]);
    if(on){ctx.fillStyle='rgba(178,124,255,.7)';ctx.beginPath();ctx.arc(p.x-8,p.y-3,3,0,6.3);ctx.arc(p.x+8,p.y-3,3,0,6.3);ctx.fill();}
  }
  ctx.restore();
}

/* ---------- Lata (target can) adapts to the biome ---------- */
/* v15 lata: one cel-shaded body (base + one hard shadow), label bands clipped inside it, ONE thick outline around the silhouette */
var CANPAL={
  0:{base:'#d9944f',shade:'#a8642c',band:'#c0392b',lid:'#c9a070',rust:'#7a3a1e'},
  1:{base:'#ffffff',shade:'#cfd6e2',band:'#ff4d5e',lid:'#ffd23f',stripes:1},
  2:{base:'#a6d86a',shade:'#5a9a2f',band:'#3d6a1c',lid:'#2a4a14'},
  3:{base:'#7ff5ea',shade:'#1fb8ad',band:'#ff5ea8',lid:'#eafffd',glow:'#ff5ea8'},
  4:{base:'#5a4a52',shade:'#2e2430',band:'#ff7a2a',lid:'#2a2024',glow:'#ff7a2a',eyes:1},
  6:{base:'#ffd23f',shade:'#d8941a',band:'#fff3a0',lid:'#fff3a0',glow:'#ffd23f',gold:1}
};
function canArt(env,y0){
  var P=CANPAL[env]||CANPAL[0],x=-7,w=14,h=27,sc=(function(m){return Math.hypot(m.a,m.b)})(ctx.getTransform())/PXS||1,i;
  rrect(x,y0,w,h,2.5);ctx.fillStyle=P.base;ctx.fill();
  ctx.save();ctx.clip();
  ctx.fillStyle=P.shade;ctx.fillRect(x+w*0.62,y0,w,h);                       // single hard shadow
  if(P.stripes){ctx.fillStyle=P.band;for(i=0;i<4;i++)ctx.fillRect(x,y0+2+i*7,w,3.5);}
  else{ctx.fillStyle=P.band;ctx.fillRect(x,y0+8,w,8);ctx.fillStyle='rgba(0,0,0,.18)';ctx.fillRect(x+w*0.62,y0+8,w,8);}
  if(P.rust){ctx.fillStyle=P.rust;ctx.fillRect(-5,y0+3,4,3);ctx.fillRect(1,y0+19,5,3);}
  if(P.gold){ctx.fillStyle='#fff';ctx.beginPath();ctx.moveTo(0,y0+9);ctx.lineTo(2,y0+13);ctx.lineTo(6,y0+13);ctx.lineTo(3,y0+16);ctx.lineTo(4,y0+20);ctx.lineTo(0,y0+17);ctx.lineTo(-4,y0+20);ctx.lineTo(-3,y0+16);ctx.lineTo(-6,y0+13);ctx.lineTo(-2,y0+13);ctx.closePath();ctx.fillStyle='#d8941a';ctx.fill();}
  if(P.eyes){ctx.fillStyle='#ffd23f';ctx.fillRect(-4,y0+10,2,3);ctx.fillRect(2,y0+10,2,3);}
  ctx.fillStyle='rgba(255,255,255,.55)';ctx.fillRect(x+2,y0+2,2,h-4);        // highlight strip
  ctx.restore();
  ctx.lineWidth=3.6/sc;ctx.strokeStyle='#2a1c18';rrect(x,y0,w,h,2.5);ctx.stroke();   // thick charcoal-brown silhouette line
  ctx.beginPath();ctx.ellipse(0,y0,7,2.6,0,0,6.3);ctx.fillStyle=P.lid;ctx.fill();ctx.lineWidth=2.4/sc;ctx.stroke();
}
var CAN_SC=1.7;
function drawCan(c){
  var env=c.env!==undefined?c.env:((G&&inGame())?(G.boss&&c.gold?6:G.cfg.env):0),sq=c.sq>0?c.sq:0,sx=1,sy=1;
  if(sq>0){var k=Math.sin(Math.min(1,sq/0.5)*Math.PI);sx=1+0.35*k;sy=1-0.3*k;}   // squash on impact / placement
  ctx.save();
  var P=CANPAL[env]||CANPAL[0];
  if(c.state==='up'){
    ctx.translate(Math.round(c.x),Math.round(c.y));
    if(P.glow){ctx.shadowColor=P.glow;ctx.shadowBlur=(P.gold?18:10)+Math.sin(anim*5)*5;}
    shadowEll(0,3,24,8,.3);ctx.scale(CAN_SC*sx,CAN_SC*sy);canArt(env,-27);
  }else if(c.carried){
    ctx.translate(Math.round(c.x),Math.round(c.y-64));
    ctx.rotate(Math.sin(anim*12)*.08);ctx.scale(CAN_SC,CAN_SC);canArt(env,-27);
  }else{
    var z=c.z||0;
    if(c.phase==='fly'){
      shadowEll(Math.round(c.x),Math.round(c.y+3),Math.max(10,26-z*.15),6,Math.max(.1,.3-z*.002));
      ctx.translate(Math.round(c.x),Math.round(c.y-z-12));ctx.rotate(c.rot||0);
      var st=Math.min(1,Math.hypot(c.vx||0,c.vy||0)/500);ctx.scale(CAN_SC*(1+.25*st),CAN_SC*(1-.12*st));   // stretch while flying fast
      canArt(env,-13.5);
    }else{
      ctx.translate(Math.round(c.x),Math.round(c.y));shadowEll(0,3,28,7,.3);
      ctx.translate(0,-9);ctx.rotate(c.rot===undefined?c.fall*1.45:c.rot);ctx.scale(CAN_SC,CAN_SC);canArt(env,-13.5);
    }
  }
  ctx.restore();
}
/* ---------- Taya adapts to the biome ---------- */
var TAYA_SKIN=[
  {shirt:'#ff5b3a',stripe:'#ffd23f',pants:'#2b2f4a',skin:'#d99467',hair:'#14121c',band:'#ffd23f',ring:'#ff3b3b'},
  {shirt:'#2f9e6a',stripe:'#ffffff',pants:'#2a3a6a',skin:'#d99467',hair:'#14121c',band:'#ffffff',ring:'#2f9e6a'},
  {shirt:'#ff4fa3',stripe:'#35e0d0',pants:'#222436',skin:'#d99467',hair:'#14121c',band:'#35e0d0',ring:'#ff4fa3'},
  {shirt:'#ffd23f',stripe:'#ff4d5e',pants:'#ff4d5e',skin:'#c98458',hair:'#14121c',hat:'salakot',ring:'#ff9a3c'},
  {shirt:'#8a5a2b',stripe:'#6fd66a',pants:'#3a5a2a',skin:'#c98458',hair:'#14121c',band:'#6fd66a',ring:'#6fd66a'},
  {shirt:'#5a3a8a',stripe:'#c9a6ff',pants:'#2a1a3a',skin:'#9ad0a0',hair:'#2a1a3a',glow:true,ring:'#b27cff'}];
var MOOD_TXT={shock:'!?',angry:'GRRR!',furious:'RAAAH!',sad:'WAAAH...',panic:'ANO NA?!',laugh:'HAHAHA!',dizzy:'@_@',scared:'AAAH!',focus:'',smug:''};
function tayaMood(T){
  if(T.force)return T.force;
  if(T.zap>0||T.state==='STUN'&&!(T.laugh>0))return 'dizzy';
  if(T.laugh>0)return 'laugh';
  if(G.rage>0&&T.state==='CHASE')return 'furious';
  if(T.sadT>0)return 'sad';
  if(T.state==='SETTING')return 'focus';
  if(T.state==='TO_CAN'||T.state==='PICK'||T.state==='CARRY')return 'panic';
  if(T.state==='CHASE')return T.react>0?'shock':(G.rage>0?'furious':'angry');
  if(G.haz&&(G.haz.type==='EARTHQUAKE'||G.haz.type==='STORM'||G.haz.type==='TORNADO'))return 'scared';
  return 'smug';
}
function drawTaya(T){
  var chase=T.state==='CHASE'&&T.react<=0,sk=TAYA_SKIN[ENVART[G.cfg.env]];
  var rage=G.rage>0,mood=tayaMood(T),bob=(mood==='laugh'?Math.abs(Math.sin(anim*16))*-4:0);
  person(T.x,T.y+bob,{scale:2.9,face:T.face,step:(T.state==='CHASE'||T.state==='TO_CAN'||T.state==='CARRY')&&Math.floor(anim*(chase?14:9))%2===0,expr:mood,
    skin:sk.skin,hair:sk.hair,shirt:rage?'#ff2a2a':sk.shirt,stripe:sk.stripe,pants:sk.pants,band:sk.band,hat:sk.hat,glow:sk.glow,bend:T.state==='SETTING'?3.6:2,ring:rage?'#ff0000':sk.ring,shoe:'#2b2f4a'});
  RR(T.x-24,T.y-112,48,18,6,rage?'#ff0000':sk.ring,2.5);
  txt('TAYA',T.x,T.y-103,12,'#fff','center',false);
  var lab=MOOD_TXT[mood],ly=T.y-136;
  if(lab){var pul=1+Math.sin(anim*14)*0.06;ctx.save();ctx.translate(T.x+34,ly);ctx.scale(pul,pul);
    ctx.globalAlpha=0.95;RR(-8-lab.length*4.5,-14,16+lab.length*9,26,10,'#fff6dc',2);ctx.globalAlpha=1;txt(lab,0,0,15,mood==='sad'?'#2a62c9':(mood==='laugh'?'#3cb371':'#c0261c'),'center',false);ctx.restore();}
  if(mood==='dizzy'){for(var q=0;q<3;q++){var an=anim*5+q*2.09;ctx.fillStyle='#ffd23f';ctx.beginPath();ctx.arc(T.x+Math.cos(an)*20,T.y-98+Math.sin(an)*6,4,0,6.2832);ctx.fill();}}
  if(mood==='furious'||(mood==='angry'&&rage)){for(q=0;q<2;q++){var k=(anim*1.5+q*.5)%1;ctx.fillStyle='rgba(255,255,255,'+(0.7-k*0.7)+')';ctx.beginPath();ctx.arc(T.x+(q?18:-18),T.y-92-k*30,5+k*6,0,6.2832);ctx.fill();}}
  if(T.state==='SETTING'){
    var pct=1-T.t/(T.tMax||G.cfg.resetTime);
    RR(T.x-34,T.y-126,68,14,6,'#fff',2.5);RR(T.x-31,T.y-123,Math.max(0,62*pct),8,3,'#ffd23f',0);
  }
  if(rage){ctx.strokeStyle='rgba(255,60,60,.8)';ctx.lineWidth=3;ctx.beginPath();ctx.ellipse(T.x,T.y+4,38+Math.sin(anim*12)*4,13,0,0,6.2832);ctx.stroke();}
}

/* =========================================================================
   v10 PIXEL-ART TOWN BACKGROUNDS (top-down autumn barrio look): built on a half-size canvas with hard rects,
   then doubled with nearest-neighbour so every pixel stays crisp.
   ========================================================================= */
var PAL_TEAL=['#17594f','#1f7a66','#3aa883','#f0c84a'],PAL_GOLD=['#a5701a','#cf9a28','#f0c84a','#fff0a0'],PAL_GREEN=['#235f2a','#34842f','#68b848','#d6ee86'],PAL_DEAD=['#241a33','#33264a','#54447a','#8878b0'],PAL_PALM=['#1f7a3c','#2f9a4a','#58c060','#d6ee86'];
function buildPixBG(env,lineY,wd){
  env=ENVART[env];
  var w=Math.round(wd/2),h=H/2,ly=Math.round(lineY/2),top=64;
  var s=document.createElement('canvas');s.width=w;s.height=h;
  var g=s.getContext('2d'),r=mulberry(env*977+wd+11),i,j;
  g.imageSmoothingEnabled=false;
  function fr(x,y,ww,hh,c){g.fillStyle=c;g.fillRect(Math.round(x),Math.round(y),Math.round(ww),Math.round(hh));}
  function disk(cx,cy,rad,c){g.fillStyle=c;cx=Math.round(cx);cy=Math.round(cy);for(var dy=-rad;dy<=rad;dy++){var dx=Math.floor(Math.sqrt(rad*rad-dy*dy+rad*0.5));g.fillRect(cx-dx,cy+dy,dx*2+1,1);}}
  function ell(cx,cy,rx,ry,c){g.fillStyle=c;cx=Math.round(cx);cy=Math.round(cy);for(var dy=-ry;dy<=ry;dy++){var dx=Math.floor(rx*Math.sqrt(Math.max(0,1-(dy*dy)/(ry*ry+0.01))));g.fillRect(cx-dx,cy+dy,dx*2+1,1);}}
  function speck(x0,y0,x1,y1,n,cols){for(var k=0;k<n;k++)fr(x0+r()*(x1-x0),y0+r()*(y1-y0),1,1,cols[Math.floor(r()*cols.length)]);}
  function ground(x0,y0,x1,y1,base,cols,n){fr(x0,y0,x1-x0,y1-y0,base);speck(x0,y0,x1,y1,n,cols);}
  function slabs(x0,y0,x1,y1,sw,sh,cols,joint){
    for(var y=y0,row=0;y<y1;y+=sh,row++)for(var x=x0-(row%2?sw/2:0);x<x1;x+=sw){
      var c=cols[Math.floor(r()*cols.length)],xx=Math.max(x,x0),ww=Math.min(x+sw,x1)-xx,hh=Math.min(sh,y1-y);if(ww<=0)continue;
      fr(xx,y,ww,hh,c);fr(xx,y,ww,1,'rgba(255,255,255,.14)');fr(xx,y+hh-1,ww,1,joint);fr(xx+ww-1,y,1,hh,joint);
    }
  }
  function tree(x,y,rad,pal){
    ell(x+2,y+1,rad,Math.max(2,rad/3),'rgba(0,0,0,.25)');
    fr(x-1,y-rad*0.7,3,rad*0.7,'#5a3a22');
    var cy=y-rad-3,k;
    disk(x,cy,rad,pal[0]);
    for(k=0;k<6;k++)disk(x+(r()-.5)*rad*1.4,cy+(r()-.5)*rad,Math.max(3,rad*.55),pal[k%2]);
    for(k=0;k<5;k++)disk(x-rad*.2+(r()-.5)*rad*.6,cy-rad*.3+(r()-.5)*rad*.4,Math.max(2,rad*.4),pal[2]);
    speck(x-rad,cy-rad,x+rad,cy+rad,rad*3,[pal[3],pal[2],pal[1]]);
  }
  function lamp(x,y){ell(x+1,y,3,1,'rgba(0,0,0,.25)');fr(x,y-26,2,26,'#3a4048');fr(x-3,y-30,8,4,'#2a3038');fr(x-2,y-29,6,2,'#ffd86a');fr(x-1,y-27,4,1,'rgba(255,230,140,.5)');}
  function hydrant(x,y){ell(x+2,y,4,1,'rgba(0,0,0,.25)');fr(x,y-8,5,8,'#c0392b');fr(x-1,y-9,7,2,'#8a2a20');fr(x-2,y-5,9,2,'#d04a3a');fr(x+1,y-6,1,6,'rgba(255,255,255,.25)');}
  function bench(x,y){ell(x+8,y+3,9,2,'rgba(0,0,0,.22)');fr(x,y-7,16,2,'#9a6a38');fr(x,y-4,16,3,'#6a4020');fr(x+1,y-1,2,4,'#3a2a20');fr(x+13,y-1,2,4,'#3a2a20');}
  function pot(x,y,c){fr(x,y-4,6,4,c||'#b8622e');fr(x,y-4,6,1,'rgba(255,255,255,.2)');disk(x+3,y-7,3,'#3a9a3a');fr(x+1,y-9,2,2,'#f08ab0');fr(x+4,y-8,2,2,'#ffd23f');}
  function bin(x,y){ell(x+4,y,5,1,'rgba(0,0,0,.22)');fr(x,y-9,9,9,'#4a6a6a');fr(x,y-10,9,2,'#6a8a8a');fr(x+1,y-8,1,8,'rgba(255,255,255,.2)');}
  function crate(x,y){fr(x,y-8,10,8,'#b8864a');fr(x,y-8,10,1,'#d8a860');fr(x+4,y-8,2,8,'#8a6030');fr(x,y-4,10,1,'#8a6030');}
  function ac(x,y){fr(x,y,12,8,'#9aa4aa');fr(x,y,12,1,'#c8d0d4');fr(x+1,y+1,6,6,'#5a6470');disk(x+4,y+4,2,'#aab4ba');fr(x+9,y+2,2,1,'#3a4048');fr(x+9,y+4,2,1,'#3a4048');}
  function vines(x,y,len,dir){var k,m;for(k=0;k<len;k++){var vx=x+(dir?k:0),vy=y+(dir?0:k);disk(vx,vy,1,r()<.5?'#2f9a5a':'#3fb86a');if(r()<.18)fr(vx+1,vy,2,2,['#f08ab0','#6ad0ff','#ffd23f','#ff8a4a'][Math.floor(r()*4)]);}}
  function bld(x,y,bw,bh,wall,roof,o){
    o=o||{};
    fr(x,y,bw,bh,wall);fr(x,y,bw,4,roof);fr(x,y+4,bw,1,'rgba(0,0,0,.3)');fr(x,y,bw,1,'rgba(255,255,255,.2)');
    for(var yy=y+9;yy<y+bh;yy+=4)fr(x,yy,bw,1,'rgba(0,0,0,.09)');
    fr(x,y+bh-3,bw,3,'rgba(0,0,0,.3)');fr(x,y,1,bh,'rgba(0,0,0,.25)');fr(x+bw-1,y,1,bh,'rgba(0,0,0,.25)');
    var n=Math.max(1,Math.floor(bw/24)),k,wx,wy,jj;
    for(k=0;k<n;k++){
      wx=x+4+k*((bw-8)/n)+2;wy=y+10+(o.low?6:0);
      fr(wx-1,wy-1,16,16,'#241c20');fr(wx,wy,14,14,o.lit&&r()<.5?'#ffd86a':(o.glass||'#6e94aa'));fr(wx,wy,14,3,'rgba(255,255,255,.28)');fr(wx+6,wy,1,14,'#241c20');fr(wx,wy+6,14,1,'#241c20');
      if(r()<.55){fr(wx-3,wy,2,14,o.shut||'#3a8a6a');fr(wx+15,wy,2,14,o.shut||'#3a8a6a');}
      fr(wx-1,wy+15,16,2,'#7a5a42');
      if(r()<.6){for(jj=0;jj<5;jj++)disk(wx+1+jj*3,wy+14,1,r()<.5?'#3a9a3a':'#58b848');if(r()<.5)fr(wx+4,wy+12,2,2,'#f08ab0');}
      else if(r()<.5&&bh>44)ac(wx+1,wy+18);
    }
    if(o.door!==false){var dx=x+bw/2-6;fr(dx-1,y+bh-18,14,18,'#241c20');fr(dx,y+bh-17,12,17,o.doorc||'#d88a3a');fr(dx+5,y+bh-17,1,17,'#241c20');fr(dx+9,y+bh-8,2,2,'#ffd23f');fr(dx-2,y+bh-20,16,2,'#7a5a42');}
    if(o.vine!==false){for(k=0;k<3;k++)vines(x+3+r()*(bw-6),y+4,6+Math.floor(r()*14),false);vines(x+2,y+5,bw-4,true);}
    if(o.sign){fr(x+bw/2-14,y+5,28,8,'#e8e0cc');fr(x+bw/2-13,y+6,26,6,o.signc||'#c0392b');}
  }
  var ground0=ly;
  /* ---------- ground + backdrop per biome ---------- */
  if(env===0){
    slabs(0,top,w,h,24,12,['#cdc6ac','#c3bc9f','#d6cfb4','#bcb598'],'rgba(70,60,40,.35)');
    speck(0,top,w,h,w*2,['#e4b04a','#c8742a','#9a5a1a','#e8d49a']);
    fr(w/2-8,top+110,16,2,'#3a3a40');fr(w/2-6,top+108,12,6,'#4a4a52');fr(w/2-4,top+110,8,2,'#2a2a30');
    var cols=[['#5d4e72','#c8623a'],['#8a5a44','#2f8a8a'],['#4a6a68','#d88a3a'],['#6a5a7a','#c8623a'],['#7a6a54','#2f8a8a']];
    for(i=0,j=0;j<w;i++){var bw=Math.max(60,Math.min(104,w-j<60?w-j:70+Math.floor(r()*34)));bld(j,0,bw,62,cols[i%5][0],cols[i%5][1],{sign:i%3===1,signc:['#2f8a8a','#c0392b','#e8873a'][i%3],low:false});j+=bw;}
    for(i=0;i<w;i+=70)fr(i,62,70,2,'rgba(0,0,0,.25)');
    for(j=0;j<2;j++){var ly3=24+j*14;for(i=0;i<w;i++)fr(i,ly3+Math.round(Math.sin(i*0.07+j)*1.5),1,1,'#3a3a40');
      for(i=8+j*9;i<w-8;i+=17){var lc=['#ffffff','#ff7ab8','#38b6ff','#ffd23f','#6adf8c'][Math.floor(r()*5)];fr(i,ly3+2,7,9,lc);fr(i,ly3+2,7,1,'rgba(0,0,0,.2)');}}
  }else if(env===1){
    ground(0,top,w,h,'#7fae3a',['#6e9a2c','#98c850','#5a8a24','#e8d49a'],w*5);
    slabs(18,top+6,w-18,ly-10,20,10,['#cfcfc4','#c4c4b8','#d8d8cc'],'rgba(60,60,60,.3)');
    fr(18,top+6,w-36,2,'#fff');fr(18,ly-12,w-36,2,'#fff');fr(18,top+6,2,ly-top-16,'#fff');fr(w-20,top+6,2,ly-top-16,'#fff');fr(w/2-1,top+6,2,ly-top-16,'#fff');
    for(i=-14;i<=14;i++)fr(w/2+Math.round(Math.cos(i/14*Math.PI*0.5)*0)-1+i,ly/2+top/2+Math.round(Math.sqrt(Math.max(0,196-i*i))),2,1,'#fff');
    bld(10,0,w-20,62,'#e8c458','#b8452e',{sign:true,signc:'#2a62c9',doorc:'#2f8a8a',lit:false});
    fr(w-26,14,2,48,'#d8d0bc');fr(w-24,14,12,8,'#d8452e');fr(w-24,18,12,4,'#2a62c9');
  }else if(env===2){                                   // PERYA SA GABI: neon night carnival
    ground(0,top,w,h,'#2a2342',['#352c52','#211b36','#3e3560'],w*5);
    for(i=0;i<w*2;i++)fr(r()*w,top+r()*(h-top),2,1,['#ff5ea8','#35e0d0','#ffd23f','#8a7aff'][Math.floor(r()*4)]);
    fr(0,0,w,64,'#0e0d26');
    for(i=0;i<70;i++)fr(r()*w,r()*40,1,1,'#e8e0ff');
    var fcx=Math.round(w*0.8),fcy=34,fr0=27;
    for(i=0;i<72;i++){var an=i/72*6.2832;fr(fcx+Math.cos(an)*fr0,fcy+Math.sin(an)*fr0,1,1,'#ff5ea8');fr(fcx+Math.cos(an)*(fr0-5),fcy+Math.sin(an)*(fr0-5),1,1,'#35e0d0');}
    for(i=0;i<12;i++){var an2=i/12*6.2832;for(j=0;j<fr0;j+=2)fr(fcx+Math.cos(an2)*j,fcy+Math.sin(an2)*j,1,1,'#9a8ad8');disk(fcx+Math.cos(an2)*fr0,fcy+Math.sin(an2)*fr0,2,['#ffd23f','#ff5ea8','#35e0d0'][i%3]);}
    disk(fcx,fcy,3,'#ffd23f');fr(fcx-1,fcy,2,36,'#5a4a8a');fr(fcx-12,62-8,24,2,'#5a4a8a');
    for(i=0,j=4;j<fcx-36;i++){var bw3=30+Math.floor(r()*14),bc=['#e8453a','#35a8e0','#f0c84a','#c04ad8'][i%4];
      fr(j,30,bw3,32,'#241a3c');fr(j,26,bw3,6,bc);for(var sx=0;sx<bw3;sx+=6)fr(j+sx,26,3,6,'#fff3dc');
      fr(j+3,36,bw3-6,14,'rgba(255,214,110,.85)');fr(j+3,36,bw3-6,2,'rgba(255,255,255,.4)');fr(j+bw3/2-2,52,4,10,bc);j+=bw3+6;}
    for(i=0;i<w;i+=2){fr(i,10+Math.round(Math.sin(i*0.12)*3),1,1,'#c8c0e8');}
    for(i=4;i<w;i+=11){var bcx=i,bcy=11+Math.round(Math.sin(i*0.12)*3);fr(bcx,bcy,5,1,'#ff5ea8');fr(bcx+1,bcy+1,3,1,'#ffd23f');fr(bcx+2,bcy+2,1,2,'#35e0d0');}
    for(i=0;i<w;i+=22)fr(i+3,5,2,2,['#ffd23f','#ff5ea8','#35e0d0'][Math.floor(i/22)%3]);
    ell(w/2,ly-8,w/2-10,10,'rgba(255,94,168,.07)');
  }else if(env===3){
    ground(0,top,w,h,'#e6d098',['#d4bc80','#f2e0aa','#c8a868','#fff3c8'],w*6);
    fr(0,0,w,8,'#ffb36a');fr(0,8,w,8,'#ffd08a');fr(0,16,w,10,'#8fd8e8');fr(0,26,w,18,'#3fb6d6');fr(0,44,w,20,'#2f9ac0');
    disk(w*0.7,12,9,'#fff0b0');fr(0,22,w,3,'rgba(255,200,120,.45)');
    for(i=0;i<3;i++){var bx=18+i*(w/3)+r()*20,by=34+(i%2)*8;fr(bx,by,26,4,'#8a4a22');fr(bx+2,by+4,22,2,'#5a2e14');fr(bx+8,by-10,1,10,'#6a4a2a');fr(bx+9,by-10,9,7,'#ffd23f');fr(bx-6,by+5,38,1,'#5a2e14');fr(bx-6,by+3,1,3,'#5a2e14');fr(bx+31,by+3,1,3,'#5a2e14');}
    for(j=0;j<3;j++)for(i=0;i<w;i+=12)fr(i+(j*5)%12,8+j*16+Math.round(Math.sin(i*0.4+j)*1.5),6,1,'rgba(255,255,255,.65)');
    fr(0,60,w,5,'#fff6d0');for(i=0;i<w;i+=8)fr(i,58+Math.round(Math.sin(i*0.5)*1.5),5,2,'#fffdf0');
    for(i=0;i<4;i++){var px=20+i*(w/4)+r()*20;tree(px,60,10,PAL_PALM);}
    fr(0,60,w,10,'#a8743c');for(i=0;i<w;i+=7)fr(i,60,1,10,'#7a5028');fr(0,60,w,1,'#c89458');fr(0,69,w,1,'#6a4220');
    for(i=6;i<w;i+=40){fr(i,70,3,8,'#6a4220');}
    for(i=0;i<12;i++){var sx=10+r()*(w-20),sy=top+10+r()*(ly-top-20);fr(sx,sy,3,2,'#f2a0b0');fr(sx+1,sy-1,1,1,'#fff');}
  }else if(env===4){                                   // PALAYAN: rice terraces, bamboo bridge, carabao, mountain fog
    var bands=['#7dbb48','#64ac3c','#8fcc58'];
    ground(0,top,w,h,'#64ac3c',['#559a34','#7dbb48','#468a2c'],w*4);
    for(i=top,j=0;i<ly+6;i+=16,j++){fr(0,i,w,16,bands[j%3]);fr(0,i+15,w,1,'#7a5a34');fr(0,i,w,1,'rgba(255,255,255,.14)');for(var kq=(j*3)%6;kq<w;kq+=6)fr(kq,i+5+(kq%4),1,3,'#3f7a2a');}
    for(i=0;i<7;i++){var px2=8+r()*(w-60),py2=top+8+r()*(ly-top-30);fr(px2,py2,20+r()*22,6,'#9bd4e6');fr(px2,py2,20,1,'#dff4fa');}
    speck(0,top,w,h,w*2,['#e8d48a','#3f7a2a']);
    fr(0,0,w,64,'#a6d0e0');fr(0,30,w,34,'#bfe0e8');
    for(i=0;i<4;i++){var mx=i*(w/3.4)+6,mh=26+Math.floor(r()*16);for(var yy=0;yy<mh;yy++)fr(mx-yy*1.5,64-mh+yy,yy*3,1,i%2?'#5f9a84':'#4f8a74');}
    for(i=0;i<3;i++)fr(0,34+i*8,w,5,'rgba(255,255,255,'+(0.3-i*.07)+')');
    for(i=0;i<w;i+=20)tree(i+8,62,8+Math.floor(r()*3),PAL_GREEN);
    bld(w-66,10,50,52,'#c8a060','#8a5a2a',{lit:false,glass:'#4a3a2a',vine:false,door:true,doorc:'#6a4a2a'});
    fr(Math.round(w*0.55),top+56,46,5,'#d8c06a');for(i=0;i<46;i+=5)fr(Math.round(w*0.55)+i,top+56,1,5,'#a8903a');fr(Math.round(w*0.55)-1,top+52,2,12,'#8a6a34');fr(Math.round(w*0.55)+45,top+52,2,12,'#8a6a34');
    var cbx=24,cby=top+92;ell(cbx,cby+4,14,3,'rgba(0,0,0,.25)');fr(cbx-12,cby-12,22,12,'#4a4038');fr(cbx+8,cby-18,10,10,'#4a4038');fr(cbx+14,cby-12,6,3,'#3a322c');fr(cbx+9,cby-21,3,5,'#d8d2c0');fr(cbx+17,cby-21,3,5,'#d8d2c0');fr(cbx-10,cby,3,6,'#2a2420');fr(cbx+4,cby,3,6,'#2a2420');
  }else{                                               // BULKANG MAYON: ash plains, glowing lava cracks, perfect cone
    ground(0,top,w,h,'#2d2527',['#3a3033','#241d1f','#463a3e','#1c1618'],w*7);
    for(i=0;i<16;i++){var lx=r()*w,ly2=top+8+r()*(ly-top-16);for(j=0;j<14;j++){fr(lx,ly2,2,1,j%3?'#ff6a2a':'#ffb02e');lx+=r()<.5?2:-1;ly2+=r()<.6?1:(r()<.5?-1:0);}}
    for(i=0;i<10;i++){var rx=r()*w,ry=top+10+r()*(ly-top-30);disk(rx,ry,3+Math.floor(r()*3),'#1a1416');fr(rx-2,ry-3,3,1,'#4a3c40');}
    var g5=g.createLinearGradient(0,0,0,64);g5.addColorStop(0,'#3a1c28');g5.addColorStop(1,'#c8603a');g.fillStyle=g5;g.fillRect(0,0,w,64);
    var vx0=Math.round(w*0.5);for(var vy=0;vy<54;vy++){var half=5+vy*1.9;fr(vx0-half,10+vy,half*2,1,vy%7===0?'#3a2a30':'#2a1e24');fr(vx0-half,10+vy,Math.max(2,half*.25),1,'#4a363c');}
    fr(vx0-7,8,14,3,'#ff7a2a');fr(vx0-3,6,6,3,'#ffd23f');
    for(i=0;i<6;i++){fr(vx0-2+(i%2)*2,11+i*8,2,8,'#ff6a2a');}
    for(i=0;i<5;i++)disk(vx0+8+i*7,4-i,6+i*2,'rgba(120,100,100,.55)');
    for(i=0;i<40;i++)fr(r()*w,r()*60,1,1,'rgba(240,230,220,.7)');
    for(i=0;i<5;i++){fr(i*36+6,50,26,14,'#1a1416');fr(i*36+10,46,16,6,'#241c1e');}
    ell(w/2,ly-10,w/2,10,'rgba(255,100,40,.07)');
  }
  /* edge foliage on the street / school / forest courts, like the reference town */
  if(env===0||env===1){
    for(i=0;i<3;i++){tree(3,top+40+i*70,9+i%2,i%2?PAL_TEAL:PAL_GOLD);tree(w-3,top+60+i*66,9+(i+1)%2,i%2?PAL_GOLD:PAL_TEAL);}
  }
  /* safe-zone strip + props */
  fr(0,ly,w,h-ly,'rgba(120,200,150,.18)');
  slabs(0,ly+2,w,h,22,11,['rgba(255,255,255,.07)','rgba(0,0,0,.05)'],'rgba(0,0,0,.18)');
  lamp(10,ly+22);lamp(w-14,ly+22);
  if(env!==3&&env!==5){bench(w*0.25,ly+30);bench(w*0.7,ly+30);pot(w*0.45,ly+30);pot(w*0.55,ly+28,'#2f8a8a');bin(w*0.88-8,ly+26);}
  else if(env===3){crate(w*0.3,ly+28);crate(w*0.33,ly+30);pot(w*0.6,ly+28);bin(w*0.85,ly+26);}
  else{bin(w*0.3,ly+26);pot(w*0.6,ly+28,'#54447a');}
  if(env===0){hydrant(w*0.12,ly+26);var tx0=Math.round(w*0.8),ty0=ly+34;ell(tx0+10,ty0+2,14,3,'rgba(0,0,0,.25)');fr(tx0,ty0-12,20,10,'#e8873a');fr(tx0+2,ty0-16,10,5,'#c8452a');fr(tx0+20,ty0-8,8,6,'#f0c84a');disk(tx0+4,ty0,3,'#222');disk(tx0+22,ty0,3,'#222');}
  var out=document.createElement('canvas');out.width=wd;out.height=H;
  var og=out.getContext('2d');og.imageSmoothingEnabled=false;try{og.filter='saturate(0.78)';}catch(e){}og.drawImage(s,0,0,wd,H);og.filter='none';
  return out;
}

""";

    private static final String JS_NEW1 = """
/* =========================================================================
   v15 block 1: BIOME FINALE BOSSES. Level 10 / 20 / 30 / 40 / 50 : the Golden Lata (Don Alikabok's drone beacon)
   is strapped to the back of a giant mechanised Taya that triggers the biome's disaster on command.
   ========================================================================= */
var BOSSES=[
  {name:'JEEPNEY-BOT',col:'#ffd23f',acc:'#e8453a',kind:'jeep',hz:['FLOOD']},
  {name:'MECHANICAL BAKUNAWA',col:'#35e0d0',acc:'#1f7a8a',kind:'bak',hz:['AMIHAN']},
  {name:'KALABAW-BOT 3000',col:'#c98458',acc:'#6a4a2a',kind:'kala',hz:['EARTHQUAKE','LANDSLIDE']},
  {name:'FERRIS-BOT',col:'#ff5ea8',acc:'#35e0d0',kind:'ferris',hz:['BROWNOUT']},
  {name:'MAYON MAGMA-BOT',col:'#ff7a2a',acc:'#3a2a30',kind:'magma',hz:['ASHFALL']}];
function bossInit(){
  var d=BOSSES[G.cfg.env],T=G.taya;
  G.boss={d:d,hp:G.cfg.hits,max:G.cfg.hits,discT:8,roar:0,hurt:0,n:0};
  G.can.gold=true;G.hazTimer=1e9;T.state='GUARD';
  G.banner={text:'BOSS FIGHT!',sub:d.name+'  -  strike down the Golden Lata!',t:3.6};
}
function bossHit(s,h){
  var B=G.boss,T=G.taya,can=G.can;
  B.hp=Math.max(0,B.hp-1);B.hurt=0.6;can.sq=0.5;can.state='up';
  T.state='STUN';T.stun=B.hp>0?1.5:3;T.laugh=0;T.zap=0.6;
  var dx=T.x-h.x,dy=T.y-h.y,d=Math.hypot(dx,dy)||1;
  T.x=clamp(T.x+dx/d*70,50,AW-50);T.y=clamp(T.y+dy/d*40,TOP+30,T.maxY);
  popBang(T.x,T.y-120,B.hp>0?pick(['BLAG!','KLANG!','PAK!']):'K.O.!','#ffd23f');
  burst(T.x,T.y-70,'#ffd23f',26,170);
  if(B.hp>0)pop(T.x,T.y-190,'GOLDEN LATA HIT!  '+B.hp+' LEFT','#fff3a0');
}
function bossUpdate(dt){
  var B=G.boss,T=G.taya,can=G.can;
  can.x=T.x;can.y=T.y;can.state='up';can.carried=false;
  if(B.hurt>0)B.hurt-=dt;
  if(B.roar>0)B.roar-=dt;
  B.discT-=dt;
  if(B.discT<=0&&!G.haz&&T.state!=='STUN'&&B.hp>0){
    B.roar=1.2;B.discT=B.hp<=B.max/2?9:13;B.n++;
    var list=B.d.hz;triggerHazard(list[B.n%list.length]);G.hazTimer=1e9;
    popBang(T.x,T.y-150,'COMMAND!','#ff5a4a');
  }
}
function bossBolts(){ctx.fillStyle='#d8dde8';for(var i=0;i<arguments.length;i+=2)ctx.fillRect(arguments[i]-1,arguments[i+1]-1,2,2);}
function drawBossRig(d,back,t){
  var k=d.kind,i,a,s;
  ctx.lineCap='round';ctx.lineJoin='round';
  if(back){
    if(k==='jeep'){
      RR(-40,-78,80,50,7,d.col,3);ctx.fillStyle=d.acc;ctx.fillRect(-38,-58,76,7);ctx.fillRect(-38,-46,76,4);
      for(i=0;i<5;i++){ctx.fillStyle=i%2?'#ffffff':d.acc;ctx.fillRect(-38+i*16,-82,10,5);}
      RR(-32,-74,22,13,2,'#bfe4ff',2);RR(10,-74,22,13,2,'#bfe4ff',2);
      RR(-24,-90,48,10,3,'#14121c',2);txt('PAK-PAK',0,-85,9,'#ffd23f','center',false);
      circ(-40,-52,4,'#fff3a0',2);circ(40,-52,4,'#fff3a0',2);
      ctx.strokeStyle='#14121c';ctx.lineWidth=2;ctx.beginPath();ctx.moveTo(-44,-30);ctx.lineTo(-50,-22);ctx.stroke();
      for(i=0;i<3;i++){ctx.globalAlpha=.5-i*.15;ctx.fillStyle='#bfc4d0';ctx.beginPath();ctx.arc(-54-i*10,-24-i*10+Math.sin(t*5+i)*2,4+i*3,0,6.3);ctx.fill();}ctx.globalAlpha=1;
    }else if(k==='bak'){
      ctx.strokeStyle='#14121c';ctx.lineWidth=24;ctx.beginPath();ctx.moveTo(-40,-10);ctx.bezierCurveTo(-76,-60,-10,-90,-30,-130);ctx.stroke();
      ctx.strokeStyle=d.col;ctx.lineWidth=17;ctx.stroke();ctx.strokeStyle=d.acc;ctx.lineWidth=5;ctx.setLineDash([5,9]);ctx.stroke();ctx.setLineDash([]);
      for(i=0;i<5;i++){var u=i/5,fx=-40+(-30+40)*u-18*Math.sin(u*3.14)*2,fy=-10-120*u;ctx.beginPath();ctx.moveTo(fx-8,fy+4);ctx.lineTo(fx-18,fy-6+Math.sin(t*4+i)*3);ctx.lineTo(fx-8,fy-8);ctx.closePath();paint('#7ff5e8',2);}
      RR(-48,-146,36,26,10,d.col,3);
      ctx.beginPath();ctx.moveTo(-50,-140);ctx.lineTo(-62,-156);ctx.lineTo(-46,-150);ctx.closePath();paint('#ffd23f',2);ctx.beginPath();ctx.moveTo(-14,-144);ctx.lineTo(-6,-160);ctx.lineTo(-22,-150);ctx.closePath();paint('#ffd23f',2);
      circ(-36,-138,4,'#ff3b3b',2);circ(-22,-138,4,'#ff3b3b',2);
      ctx.fillStyle='#fff';ctx.fillRect(-42,-128,4,6);ctx.fillRect(-28,-128,4,6);
      circ(-8,-126,11,'#ffe9a8',2);circ(-4,-129,9,d.col,0);
    }else if(k==='ferris'){
      var ra=t*0.8;ctx.strokeStyle='#14121c';ctx.lineWidth=9;ctx.beginPath();ctx.arc(0,-82,56,0,6.2832);ctx.stroke();ctx.strokeStyle=d.col;ctx.lineWidth=5;ctx.stroke();
      ctx.beginPath();ctx.arc(0,-82,32,0,6.2832);ctx.strokeStyle='#14121c';ctx.lineWidth=5;ctx.stroke();
      for(i=0;i<8;i++){a=ra+i*0.785;ctx.strokeStyle=d.acc;ctx.lineWidth=2;ctx.beginPath();ctx.moveTo(0,-82);ctx.lineTo(Math.cos(a)*56,-82+Math.sin(a)*56);ctx.stroke();
        var gx=Math.cos(a)*56,gy=-82+Math.sin(a)*56;RR(gx-7,gy-4,14,12,3,['#ffd23f','#35e0d0','#ff5ea8','#8f6bff'][i%4],2.5);
        ctx.fillStyle=Math.floor(t*4+i)%2?'#fff3a0':'#6a5a20';ctx.fillRect(gx-1,gy-8,2,3);}
      circ(0,-82,8,'#ffd23f',3);
      for(i=0;i<16;i++){a=i*0.3927;ctx.fillStyle=Math.floor(t*6+i)%2?'#fff3a0':'#7a5a20';ctx.beginPath();ctx.arc(Math.cos(a)*62,-82+Math.sin(a)*62,2,0,6.3);ctx.fill();}
    }else if(k==='magma'){
      ctx.fillStyle='rgba(255,122,42,.32)';ctx.beginPath();ctx.arc(0,-72,66+Math.sin(t*6)*4,0,6.2832);ctx.fill();
      ctx.beginPath();ctx.moveTo(-54,-20);ctx.lineTo(-40,-96);ctx.lineTo(-20,-128);ctx.lineTo(0,-150);ctx.lineTo(20,-128);ctx.lineTo(40,-96);ctx.lineTo(54,-20);ctx.closePath();paint('#4a3236',3);
      ctx.strokeStyle='#ff7a2a';ctx.lineWidth=3;ctx.beginPath();ctx.moveTo(0,-150);ctx.lineTo(-6,-120);ctx.lineTo(4,-96);ctx.lineTo(-8,-60);ctx.moveTo(-26,-110);ctx.lineTo(-18,-80);ctx.moveTo(26,-110);ctx.lineTo(20,-70);ctx.stroke();
      for(i=0;i<4;i++){var ey2=(t*40+i*30)%110;ctx.fillStyle='rgba(255,210,63,'+(1-ey2/110)+')';ctx.beginPath();ctx.arc(-24+i*16,-150-ey2,3,0,6.3);ctx.fill();}
    }else if(k==='kala'){
      RR(-44,-86,88,50,12,d.col,3);ctx.fillStyle=d.acc;ctx.fillRect(-42,-62,84,6);
      RR(-30,-96,60,16,6,'#8a5a2a',3);for(i=0;i<5;i++){ctx.fillStyle='#3a8a3a';ctx.fillRect(-26+i*11,-104,6,10);}
      ctx.strokeStyle='#14121c';ctx.lineWidth=5;ctx.beginPath();ctx.moveTo(-46,-70);ctx.lineTo(-60,-60);ctx.lineTo(-54,-30);ctx.stroke();
    }
  }else{
    if(k==='kala'){
      for(s=-1;s<=1;s+=2){ctx.strokeStyle='#14121c';ctx.lineWidth=11;ctx.beginPath();ctx.moveTo(s*14,-112);ctx.quadraticCurveTo(s*52,-112,s*46,-148);ctx.stroke();ctx.strokeStyle='#f4ecd6';ctx.lineWidth=6;ctx.stroke();}
      RR(-16,-72,32,10,3,'#6a4a2a',2.5);ctx.fillStyle=d.col;ctx.fillRect(-14,-70,28,2);
      circ(0,-62,5,'#ffd23f',2.5);
    }else if(k==='magma'){
      for(var j=0;j<7;j++){var fh=24+Math.sin(t*9+j*1.7)*9;ctx.beginPath();ctx.moveTo(-24+j*8-5,-116);ctx.lineTo(-24+j*8,-116-fh);ctx.lineTo(-24+j*8+5,-116);ctx.closePath();paint(j%2?'#ff7a2a':'#ffd23f',2);}
      ctx.strokeStyle='#ff7a2a';ctx.lineWidth=2;ctx.beginPath();ctx.moveTo(-10,-56);ctx.lineTo(-4,-46);ctx.lineTo(-12,-36);ctx.moveTo(10,-56);ctx.lineTo(6,-44);ctx.stroke();
    }else if(k==='jeep'){
      circ(-32,-30,11,'#2a2a30',3);circ(32,-30,11,'#2a2a30',3);circ(-32,-30,4,'#d8dde8',1);circ(32,-30,4,'#d8dde8',1);
      RR(-10,-54,20,12,3,'#d8dde8',2);for(i=0;i<3;i++){ctx.fillStyle='#4a5060';ctx.fillRect(-8+i*6,-52,3,8);}
    }else if(k==='bak'){
      for(s=-1;s<=1;s+=2){ctx.beginPath();ctx.moveTo(s*10,-116);ctx.lineTo(s*22,-134);ctx.lineTo(s*20,-112);ctx.closePath();paint('#ffd23f',2);}
    }else if(k==='ferris'){
      RR(-22,-122,44,8,3,d.col,2.5);for(i=0;i<5;i++){circ(-16+i*8,-118,2.2,Math.floor(t*5+i)%2?'#fff3a0':'#ff5ea8',0);}
    }
  }
}
function bossArmor(d,t){
  /* robot plating shared by every finale boss (units: boss rig space, feet at 0,0) */
  var s,i;
  RR(-16,-66,32,28,6,'#6c7488',3);RR(-12,-62,24,10,3,d.col,2);
  ctx.fillStyle=d.acc;for(i=0;i<4;i++)ctx.fillRect(-12+i*7,-48,5,3);
  circ(0,-57,3.5,Math.floor(t*3)%2?'#ffffff':'#fff3a0',1.5);
  bossBolts(-14,-64,14,-64,-14,-40,14,-40);
  RR(-14,-93,30,9,4,'rgba(255,40,40,.82)',2);ctx.fillStyle='rgba(255,255,255,.55)';ctx.fillRect(-10+((t*26)%24),-91,3,5);
  for(s=-1;s<=1;s+=2){
    ctx.strokeStyle='#14121c';ctx.lineWidth=7;ctx.beginPath();ctx.moveTo(s*26,-64);ctx.lineTo(s*32,-46);ctx.lineTo(s*28,-30);ctx.stroke();
    ctx.strokeStyle='#9aa4b8';ctx.lineWidth=4;ctx.stroke();
    RR(s*32-6,-38,12,12,3,d.col,2.5);ctx.fillStyle=d.acc;ctx.fillRect(s*32-5,-33,10,2);
    RR(s*26-4,-76,8,16,2,'#6c7488',2);circ(s*26,-78,3,'#14121c',0);
  }
  for(s=-1;s<=1;s+=2){ctx.strokeStyle='#9aa4b8';ctx.lineWidth=3;ctx.beginPath();ctx.moveTo(s*8,-22);ctx.lineTo(s*8,-8);ctx.stroke();RR(s*8-4,-12,8,6,2,d.col,2);}
}
function drawBoss(T){
  var B=G.boss,d=B.d,sc=1.6,t=anim;
  ctx.save();ctx.translate(T.x,T.y);
  shadowEll(0,4,60,14,.35);
  ctx.save();ctx.scale(sc,sc);
  drawBossRig(d,true,t);
  ctx.restore();
  /* Golden Lata strapped to its back */
  var lx=-T.face*30,ly=-98+(B.hurt>0?Math.sin(t*40)*3:0);
  ctx.save();ctx.translate(lx,ly);ctx.rotate(B.hurt>0?Math.sin(t*30)*.2:0);
  drawCan({x:0,y:0,state:'up',fall:0,env:6,sq:G.can.sq});
  ctx.strokeStyle='#14121c';ctx.lineWidth=4;ctx.beginPath();ctx.moveTo(-14,-10);ctx.lineTo(T.face*10,38);ctx.moveTo(14,-10);ctx.lineTo(T.face*10+8,38);ctx.stroke();
  ctx.restore();
  ctx.restore();
  ctx.save();ctx.translate(T.x,T.y);ctx.scale(sc,sc);ctx.translate(-T.x,-T.y);
  if(B.hurt>0)ctx.globalAlpha=.6+.4*Math.sin(anim*50);
  drawTaya(T);
  ctx.globalAlpha=1;
  ctx.restore();
  ctx.save();ctx.translate(T.x,T.y);ctx.scale(sc,sc);
  drawBossRig(d,false,t);
  bossArmor(d,t);
  /* metal shoulder plates + antenna */
  for(var s=-1;s<=1;s+=2){circ(s*24,-66,8,'#9aa4b8',3);circ(s*24,-67,3,d.col,0);}
  ctx.strokeStyle='#14121c';ctx.lineWidth=3;ctx.beginPath();ctx.moveTo(0,-118);ctx.lineTo(0,-132);ctx.stroke();circ(0,-134,4,Math.floor(anim*4)%2?'#ff3b3b':'#6a0a0a',2);
  ctx.restore();
  if(B.roar>0){ctx.strokeStyle='rgba(255,90,60,'+clamp(B.roar,0,1)+')';ctx.lineWidth=5;for(var r=0;r<3;r++){ctx.beginPath();ctx.ellipse(T.x,T.y-70,50+r*30+(1.2-B.roar)*80,20+r*10+(1.2-B.roar)*30,0,0,6.2832);ctx.stroke();}}
}
function drawBossHUD(){
  var B=G.boss;if(!B)return;
  var w=380,x=AW/2-w/2,y=82;
  RR(x-8,y-4,w+16,44,12,'rgba(16,19,28,.88)',3);
  txt(B.d.name,AW/2,y+10,16,B.d.col,'center');
  var n=B.max,sw=(w-6)/n,i;
  for(i=0;i<n;i++){ctx.fillStyle=i<B.hp?'#ffd23f':'#3a3f58';ctx.fillRect(x+3+i*sw+1,y+22,sw-3,12);if(i<B.hp){ctx.fillStyle='rgba(255,255,255,.4)';ctx.fillRect(x+3+i*sw+1,y+22,sw-3,3);}}
}

""";

    private static final String JS_NEW2 = """
/* =========================================================================
   v15 block 2: hand-drawn cartoon VFX (comic bangs, dust puffs, ripples, smear frames) + Tansan collectibles
   ========================================================================= */
function popBang(x,y,text,col){(G.bangs=G.bangs||[]).push({x:x,y:y,text:text,col:col||'#ffd23f',t0:G.time,rot:(Math.random()-.5)*0.3,pts:7+Math.floor(Math.random()*4)});}
function dust(x,y,n,col){var p=(G.puffs=G.puffs||[]);for(var i=0;i<(n||4);i++)p.push({x:x+rnd(-10,10),y:y+rnd(-3,3),vx:rnd(-40,40),vy:rnd(-30,-6),r:rnd(5,9),t:0,life:rnd(.35,.6),col:col||'#f1e6cf'});}
function ripple(x,y,col){var r=(G.ripples=G.ripples||[]);if(r.length<26)r.push({x:x,y:y,t:0,life:rnd(.7,1.1),col:col||'rgba(235,248,255,.8)'});}
function vfxUpdate(dt){
  var i,a;
  if(G.puffs){for(i=0;i<G.puffs.length;i++){a=G.puffs[i];a.t+=dt;a.x+=a.vx*dt;a.y+=a.vy*dt;a.r+=dt*26;}G.puffs=G.puffs.filter(function(p){return p.t<p.life;});}
  if(G.ripples){for(i=0;i<G.ripples.length;i++)G.ripples[i].t+=dt;G.ripples=G.ripples.filter(function(p){return p.t<p.life;});}
  if(G.bangs)G.bangs=G.bangs.filter(function(b){return G.time-b.t0<1.0;});
  var raining=G.haz&&(G.haz.type==='FLOOD'||G.haz.type==='AMIHAN');
  if(raining&&Math.random()<dt*14)ripple(rnd(40,AW-40),rnd(300,G.line+60));
  for(i=0;i<G.slippers.length;i++){
    a=G.slippers[i];
    if(a.state==='fly'||a.state==='rico'){a.ht=(a.ht||0)-dt;if(a.ht<=0){a.ht=0.022;(a.hist=a.hist||[]).unshift({x:a.x,y:a.y,z:a.z});if(a.hist.length>4)a.hist.pop();}}
    else a.hist=null;
  }
  for(i=0;i<G.hitters.length;i++){a=G.hitters[i];if(a.moving&&Math.random()<dt*7&&a.y<G.line+80)dust(a.x,a.y+2,1);}
  tansanUpdate(dt);
}
function drawVfxGround(){
  if(G.ripples)for(var i=0;i<G.ripples.length;i++){var r=G.ripples[i],p=r.t/r.life;ctx.globalAlpha=1-p;ctx.strokeStyle=r.col;ctx.lineWidth=2;ctx.beginPath();ctx.ellipse(r.x,r.y,6+p*30,2.5+p*11,0,0,6.2832);ctx.stroke();ctx.globalAlpha=1;}
}
function drawVfxTop(){
  var i;
  if(G.puffs)for(i=0;i<G.puffs.length;i++){var p=G.puffs[i],q=p.t/p.life;ctx.globalAlpha=(1-q)*0.9;ctx.fillStyle=p.col;ctx.beginPath();ctx.arc(p.x,p.y,p.r,0,6.2832);ctx.fill();ctx.lineWidth=2;ctx.strokeStyle='rgba(90,70,50,'+(1-q)*.8+')';ctx.stroke();ctx.globalAlpha=1;}
  if(G.bangs)for(i=0;i<G.bangs.length;i++){
    var b=G.bangs[i],age=G.time-b.t0,sc=age<0.18?easeBack(age/0.18):1,al=age>0.7?1-(age-0.7)/0.3:1;
    ctx.save();ctx.globalAlpha=al;ctx.translate(b.x,b.y-age*26);ctx.rotate(b.rot);ctx.scale(sc,sc);
    var n=b.pts*2,R1=52+b.text.length*5,R2=R1*0.62;
    ctx.beginPath();for(var k=0;k<n;k++){var a=k/n*6.2832,rr=k%2?R2:R1;ctx.lineTo(Math.cos(a)*rr*1.25,Math.sin(a)*rr*0.7);}ctx.closePath();
    ctx.fillStyle=b.col;ctx.fill();ctx.lineWidth=6;ctx.strokeStyle='#2a1c18';ctx.lineJoin='round';ctx.stroke();
    txtT(b.text,0,0,Math.max(14,Math.floor(34-b.text.length*1.6)),'#2a1c18','center');
    ctx.restore();
  }
}
/* 3-frame motion smear: tapered arcs trailing a fast tsinelas */
function smear(s){
  if(!s.hist||!s.hist.length)return;
  var heavy=s.heavy,w0=heavy?22:13,i;
  for(i=s.hist.length-1;i>=0;i--){
    var h=s.hist[i],nx=i>0?s.hist[i-1]:{x:s.x,y:s.y,z:s.z},al=0.16+0.12*(s.hist.length-i)/s.hist.length;
    var x0=h.x,y0=h.y-h.z-14,x1=nx.x,y1=nx.y-nx.z-14,dx=x1-x0,dy=y1-y0,L=Math.hypot(dx,dy)||1,px=-dy/L,py=dx/L,ww=w0*(1-i*0.22);
    ctx.globalAlpha=al*(heavy?1.4:1);ctx.fillStyle=s.col||(heavy?'#ff8a3a':'#ffffff');
    ctx.beginPath();ctx.moveTo(x0,y0);ctx.lineTo(x1+px*ww,y1+py*ww);ctx.lineTo(x1-px*ww,y1-py*ww);ctx.closePath();ctx.fill();
  }
  ctx.globalAlpha=1;
}

/* ---------- TANSAN (bottle caps): hidden collectibles that only appear while a disaster is active ---------- */
function tansanUpdate(dt){
  var i,k;
  G.tans=G.tans||[];
  if(G.haz&&!G.endlessNoTan){
    G.tanT=(G.tanT||2.5)-dt;
    if(G.tanT<=0&&G.tans.length<2){
      G.tanT=rnd(3.2,5.5);
      for(var tries=0;tries<30;tries++){
        var x=rnd(70,AW-70),y=rnd(320,G.line-40);
        if(!inObstacle(x,y,26)){G.tans.push({x:x,y:y,t:9,ph:Math.random()*6});break;}
      }
    }
  }
  for(i=0;i<G.tans.length;i++){
    var c=G.tans[i];c.t-=dt;
    for(k=0;k<G.hitters.length;k++){var h=G.hitters[k];if(Math.hypot(h.x-c.x,(h.y-c.y)*1.2)<34){c.dead=true;SAVE.tansan=(SAVE.tansan||0)+1;G.tanGot=(G.tanGot||0)+1;pop(c.x,c.y-50,'+1 TANSAN','#9ff0ff');popBang(c.x,c.y-30,'CLINK!','#9ff0ff');SFX.coin();burst(c.x,c.y-14,'#9ff0ff',10,70);break;}}
    if(c.t<=0)c.dead=true;
  }
  G.tans=G.tans.filter(function(c){return !c.dead;});
}
function tansanIcon(x,y,r){
  ctx.save();ctx.translate(x,y);
  ctx.beginPath();for(var i=0;i<12;i++){var a=i/12*6.2832;ctx.lineTo(Math.cos(a)*r*(i%2?0.84:1),Math.sin(a)*r*(i%2?0.84:1));}ctx.closePath();
  ctx.fillStyle='#bfeeff';ctx.fill();ctx.lineWidth=Math.max(1.5,r/6);ctx.strokeStyle='#1f4a5e';ctx.stroke();
  ctx.beginPath();ctx.arc(0,0,r*0.55,0,6.2832);ctx.fillStyle='#e22b3a';ctx.fill();ctx.stroke();
  ctx.fillStyle='rgba(255,255,255,.7)';ctx.fillRect(-r*0.35,-r*0.35,r*0.3,r*0.12);
  ctx.restore();
}
function drawTansans(){
  if(!G.tans)return;
  for(var i=0;i<G.tans.length;i++){var c=G.tans[i],f=c.t<2?(Math.floor(anim*10)%2?0.35:1):1,bob=Math.sin(anim*4+c.ph)*3;
    ctx.globalAlpha=f;shadowEll(c.x,c.y+4,12,4,.25);tansanIcon(c.x,c.y-14+bob,13);
    ctx.fillStyle='rgba(159,240,255,'+(0.35+0.3*Math.sin(anim*6+c.ph))+')';ctx.beginPath();ctx.arc(c.x,c.y-14+bob,19,0,6.2832);ctx.fill();ctx.globalAlpha=1;}
}

/* ---------- Jun-Jun's Rambo Ricochet: heavy slipper that bounces off walls and cover ---------- */
function ricoStart(s,blocked){
  var dx=s.tx-s.x0,dy=s.ty-s.y0,L=Math.hypot(dx,dy)||1,sp=600;
  s.state='rico';s.vx=dx/L*sp;s.vy=dy/L*sp;s.life=1.5;s.z=12;
  if(blocked){s.vx*=-0.6;s.vy*=-0.6;}
  s.rico--;popBang(s.x,s.y-30,'BOING!','#ff9a3a');
}
function ricoUpdate(s,dt){
  var steps=3,k,j,can=G.can;
  s.life-=dt;s.rot+=dt*18;
  for(k=0;k<steps;k++){
    var nx=s.x+s.vx*dt/steps,ny=s.y+s.vy*dt/steps,bounced=false;
    if(nx<30||nx>AW-30){s.vx=-s.vx;nx=clamp(nx,30,AW-30);bounced=true;}
    if(ny<TOP+25||ny>G.line+40){s.vy=-s.vy;ny=clamp(ny,TOP+25,G.line+40);bounced=true;}
    for(j=0;j<G.obst.length;j++){
      var o=G.obst[j];
      if(o.block&&Math.abs(nx-o.x)<o.w/2+6&&Math.abs(ny-o.y)<o.h/2+6){
        if(Math.abs(s.x-o.x)>=o.w/2+6)s.vx=-s.vx;else s.vy=-s.vy;
        nx=s.x;ny=s.y;bounced=true;break;
      }
    }
    s.x=nx;s.y=ny;
    if(bounced){s.rico--;dust(s.x,s.y,3);popBang(s.x,s.y-30,'BOING!','#ff9a3a');SFX.click&&SFX.click();}
    s.z=12+Math.abs(Math.sin(s.life*9))*10;
    if(can.state==='up'&&Math.hypot(s.x-can.x,s.y-can.y)<(G.boss?60:G.cfg.canR*1.2+6)){s.perfect=true;hitCan(s);return;}
    if(s.rico<0){landSlipper(s,false);return;}
  }
  if(s.life<=0)landSlipper(s,false);
}
/* ---------- Taba's Lindol Stomp shockwave ---------- */
function shockwave(x,y,r){(G.shocks=G.shocks||[]).push({x:x,y:y,r:r,t0:G.time});dust(x,y,14);}
/* ---------- Boyet's decoy (teks pile + smoke) and Nene's wind aura, drawn on the ground layer ---------- */
function drawDecoys(){
  var i,d=G.decoy;
  if(G.shocks){G.shocks=G.shocks.filter(function(s){return G.time-s.t0<0.7;});
    for(i=0;i<G.shocks.length;i++){var s=G.shocks[i],p=(G.time-s.t0)/0.7;ctx.globalAlpha=1-p;ctx.strokeStyle='#ffd9a0';ctx.lineWidth=6*(1-p)+2;ctx.beginPath();ctx.ellipse(s.x,s.y,s.r*p,s.r*p*0.45,0,0,6.2832);ctx.stroke();ctx.globalAlpha=1;}}
  if(d&&d.t>0){
    d.t-=1/60;
    ctx.fillStyle='rgba(205,212,228,'+Math.min(0.4,d.t/2)+')';ctx.beginPath();ctx.ellipse(d.x,d.y,d.r,d.r*0.7,0,0,6.2832);ctx.fill();
    for(i=0;i<5;i++){var a=anim*0.7+i*1.26;ctx.fillStyle='rgba(225,230,242,.45)';ctx.beginPath();ctx.arc(d.x+Math.cos(a)*d.r*0.5,d.y+Math.sin(a)*d.r*0.35,d.r*0.28,0,6.2832);ctx.fill();}
    var cols=['#e8453a','#35a8e0','#f0c84a','#6adf8c','#c04ad8'];
    for(i=0;i<5;i++){ctx.save();ctx.translate(d.x-16+i*8,d.y+Math.sin(i)*2);ctx.rotate(-0.5+i*0.25);RR(-9,-12,18,24,3,cols[i],2.5);ctx.restore();}
  }
  for(i=0;i<G.hitters.length;i++){var h=G.hitters[i],lv=sl(h,'MARIA',3);if(lv>0){ctx.strokeStyle='rgba(159,233,255,'+(0.25+0.15*Math.sin(anim*3))+')';ctx.lineWidth=3;ctx.setLineDash([8,8]);ctx.beginPath();ctx.ellipse(h.x,h.y,170,70,0,0,6.2832);ctx.stroke();ctx.setLineDash([]);}}
}

/* ---------- Apoy Slipper: burns puddles, tide zones and ash where it lands ---------- */
function burnAt(x,y){
  var i,n=0,p;
  for(i=G.pud.length-1;i>=0;i--){
    p=G.pud[i];
    if(Math.abs(x-p.x)<p.rx+40&&Math.abs(y-p.y)<p.ry+40){G.pud.splice(i,1);n++;}
  }
  if(G.zones)for(i=G.zones.length-1;i>=0;i--){
    p=G.zones[i];
    if(p.type==='tide'&&Math.hypot(x-p.x,y-p.y)<p.r+60){G.zones.splice(i,1);n++;}
  }
  if(G.haz&&G.haz.ash>0){G.haz.ash=Math.max(0,G.haz.ash-0.35);n++;}
  for(i=0;i<10;i++)G.parts.push({x:x+rnd(-20,20),y:y+rnd(-10,10),vx:rnd(-40,40),vy:rnd(-110,-40),t:0,life:.7,col:pick(['#ff5a1f','#ffd23f','#ffffff']),size:rnd(5,10),g:-20});
  if(n)pop(x,y-50,'SIZZLE!','#ffb347');
}

""";

    private static final String JS_RENDER2 = """

/* ---------- pogs: round milk-cap style collectibles ---------- */
function pogIcon(type,x,y,s){
  ctx.save();ctx.translate(x,y);ctx.scale(s,s);
  if(type==='GHOST'){
    ctx.beginPath();ctx.moveTo(-9,10);ctx.lineTo(-9,-3);ctx.arc(0,-3,9,Math.PI,0);ctx.lineTo(9,10);
    ctx.lineTo(5,6);ctx.lineTo(1.5,10);ctx.lineTo(-2,6);ctx.lineTo(-5.5,10);ctx.closePath();paint('#ffffff',2);
    circ(-3.5,-3,1.8,INK,0);ctx.fillStyle=INK;ctx.fill();circ(3.5,-3,1.8,INK,0);ctx.fill();
  }else if(type==='SPEED'){
    ctx.beginPath();ctx.moveTo(3,-12);ctx.lineTo(-8,3);ctx.lineTo(-1,3);ctx.lineTo(-4,13);ctx.lineTo(9,-3);ctx.lineTo(1,-3);ctx.closePath();paint('#fff36b',2);
  }else if(type==='MULTI'){
    var a=[-0.55,0,0.55],i;
    for(i=0;i<3;i++){ctx.save();ctx.translate(a[i]*14,i===1?-1:2);ctx.rotate(a[i]);ctx.scale(.55,.55);RR(-5,-11,10,22,5,'#ff8a2b',2.6);ctx.restore();}
  }else if(type==='SPARE'){
    ctx.save();ctx.rotate(-0.5);RR(-5,-11,10,22,5,'#ff8a2b',2.6);ctx.restore();
    ctx.fillStyle='#ffffff';ctx.fillRect(-1.6,-1,3.2,10);ctx.fillRect(-5,2.2,10,3.2);ctx.strokeStyle=INK;ctx.lineWidth=1.4;ctx.strokeRect(-1.6,-1,3.2,10);
  }else if(type==='FREEZE'){
    ctx.strokeStyle='#ffffff';ctx.lineWidth=3;for(var f=0;f<3;f++){ctx.save();ctx.rotate(f*1.047);ctx.beginPath();ctx.moveTo(-11,0);ctx.lineTo(11,0);ctx.stroke();ctx.restore();}
    circ(0,0,3,'#ffffff',0);ctx.fillStyle='#ffffff';ctx.fill();
  }else if(type==='MUD'){
    ctx.beginPath();ctx.moveTo(-10,6);ctx.quadraticCurveTo(-12,-8,-2,-8);ctx.quadraticCurveTo(10,-12,10,2);ctx.quadraticCurveTo(12,9,0,9);ctx.closePath();paint('#7a4a22',2);
    circ(-3,-1,2,'#a07a4a',0);ctx.fillStyle='#a07a4a';ctx.fill();circ(4,3,1.6,'#a07a4a',0);ctx.fill();
  }else if(type==='REVERSE'){
    ctx.strokeStyle='#ffffff';ctx.lineWidth=3;ctx.beginPath();ctx.arc(0,0,9,0.4,3.4);ctx.stroke();ctx.beginPath();ctx.arc(0,0,9,3.5,6.5);ctx.stroke();
    ctx.fillStyle='#ffffff';ctx.beginPath();ctx.moveTo(-10,-4);ctx.lineTo(-5,-8);ctx.lineTo(-4,-1);ctx.fill();ctx.beginPath();ctx.moveTo(10,4);ctx.lineTo(5,8);ctx.lineTo(4,1);ctx.fill();
  }else if(type==='JITTER'){
    ctx.strokeStyle='#ffffff';ctx.lineWidth=3.5;ctx.beginPath();ctx.moveTo(-10,0);ctx.lineTo(-6,-8);ctx.lineTo(-2,8);ctx.lineTo(2,-8);ctx.lineTo(6,8);ctx.lineTo(10,0);ctx.stroke();
  }else if(type==='RAGE'){
    circ(0,0,10,'#ffffff',2);ctx.strokeStyle=INK;ctx.lineWidth=2.4;ctx.beginPath();ctx.moveTo(-7,-6);ctx.lineTo(-2,-3);ctx.moveTo(7,-6);ctx.lineTo(2,-3);ctx.moveTo(-4,6);ctx.lineTo(4,6);ctx.stroke();
    circ(-3.5,-1,1.4,INK,0);ctx.fillStyle=INK;ctx.fill();circ(3.5,-1,1.4,INK,0);ctx.fill();
  }else{
    circ(0,0,11,'#ffc928',2.2);circ(0,0,7.2,'#ffe27a',0);
    ctx.fillStyle='#9a6a00';ctx.fillRect(-1.6,-5,3.2,10);ctx.fillRect(-4,-5,8,2);ctx.fillRect(-4,3,8,2);
    ctx.fillStyle=INK;ctx.font='800 9px '+FONT;ctx.textAlign='center';ctx.textBaseline='middle';ctx.fillText('x2',0,16);
  }
  ctx.restore();
}
function drawPog(type,x,y,r,spin){
  var d=POGS[type],i;
  ctx.save();ctx.translate(x,y);
  var sx=spin?Math.max(.35,Math.abs(Math.cos(spin))):1;
  ctx.scale(sx,1);
  var gr=ctx.createRadialGradient(-r*.3,-r*.3,r*.1,0,0,r);gr.addColorStop(0,d.bg);gr.addColorStop(1,d.bg2);
  circ(0,0,r,gr,3);
  ctx.strokeStyle='rgba(255,255,255,.75)';ctx.lineWidth=2.5;ctx.setLineDash([4,5]);ctx.beginPath();ctx.arc(0,0,r*.82,0,6.2832);ctx.stroke();ctx.setLineDash([]);
  pogIcon(type,0,-1,r/21);
  ctx.restore();
}
function drawPogPickup(k){
  if(k.t<4&&Math.floor(anim*8)%2)return;
  var y=k.y-30+Math.sin(anim*3+k.bob)*5;
  shadowEll(k.x,k.y+6,20,6,.25);
  ctx.save();ctx.strokeStyle='rgba(255,255,255,.85)';ctx.lineWidth=3;ctx.setLineDash([7,6]);ctx.lineDashOffset=-anim*20;
  ctx.beginPath();ctx.arc(k.x,y,36,0,6.2832);ctx.stroke();ctx.restore();
  if(k.type){
    var dd=POGS[k.type];drawPog(k.type,k.x,y,26,anim*2+k.bob);
    if(dd.trap){ctx.save();ctx.strokeStyle='#ff4a4a';ctx.lineWidth=4;ctx.beginPath();ctx.arc(k.x,y,31,0,6.2832);ctx.stroke();ctx.restore();}
    RR(k.x-44,y-58,88,20,8,dd.trap?'#c0392b':'#1f7a45',2.5);txt(dd.short,k.x,y-48,12,'#fff','center',false);
  }else drawMystery(k.x,y,26,anim*2+k.bob);
}
function drawMystery(x,y,r,spin){
  ctx.save();ctx.translate(x,y);
  var sx=Math.max(.35,Math.abs(Math.cos(spin)));ctx.scale(sx,1);
  var hue=(anim*90)%360,gr=ctx.createRadialGradient(-r*.3,-r*.3,r*.1,0,0,r);
  gr.addColorStop(0,'hsl('+hue+',90%,75%)');gr.addColorStop(1,'hsl('+((hue+60)%360)+',70%,45%)');
  circ(0,0,r,gr,3);
  ctx.strokeStyle='rgba(255,255,255,.8)';ctx.lineWidth=2.5;ctx.setLineDash([4,5]);ctx.beginPath();ctx.arc(0,0,r*.82,0,6.2832);ctx.stroke();ctx.setLineDash([]);
  ctx.restore();
  txt('?',x,y+1,r*1.2,'#ffffff','center');
}

/* ---------- hitters, aim reticle, power meter ---------- */
function drawHitter(h){
  if(h.inv>0&&Math.floor(anim*14)%2)return;
  var ch=CHARS[h.char]||CHARS.JUAN;
  person(h.x,h.y,{face:h.face,step:h.moving&&Math.floor(anim*10)%2===0,skin:ch.skin,hair:ch.hair,
    shirt:ch.shirt,stripe:'#ffffff',pants:ch.pants,hat:ch.hat==='salakot'?'salakot':'',band:ch.hat==='band'?'#ffffff':'',ring:h.col,up:h.meter.on||h.throwAnim>0,shoe:'#fff'});
  RR(h.x-17,h.y-94,34,18,6,h.col,2.5);
  txt(h.name,h.x,h.y-85,13,'#171b28','center',false);
  if(h.ghost){circ(h.x,h.y-108,7,'rgba(220,235,255,.95)',2);}
  if(h.multi>0){for(var m=0;m<3;m++)circ(h.x-12+m*12,h.y-110,4,'#ffb347',2);}
  if(h.boost>0){ctx.strokeStyle='#f5c542';ctx.lineWidth=3;for(var i=0;i<3;i++){ctx.beginPath();ctx.moveTo(h.x-h.face*30,h.y-14-i*9);ctx.lineTo(h.x-h.face*(46+i*6),h.y-14-i*9);ctx.stroke();}}
  if(h.mud>0){ctx.fillStyle='rgba(120,80,40,.6)';ctx.beginPath();ctx.ellipse(h.x,h.y+6,32,11,0,0,6.2832);ctx.fill();}
  if(h.rev>0){txt('?!',h.x+20,h.y-112+Math.sin(anim*10)*3,18,'#ff9fd0','center');}
  if(h.jit>0){ctx.strokeStyle='#c9f06a';ctx.lineWidth=2;ctx.beginPath();ctx.arc(h.x+Math.sin(anim*40)*2,h.y-60,26,0.2,1.2);ctx.stroke();}
  if(h.dbl>0){ctx.strokeStyle='#ffd23f';ctx.lineWidth=3;ctx.beginPath();ctx.ellipse(h.x,h.y+4,34+Math.sin(anim*8)*3,12,0,0,6.2832);ctx.stroke();}
  if(h.stun>0){for(var s=0;s<3;s++){var a=anim*7+s*2.09;starPath(h.x+Math.cos(a)*22,h.y-104+Math.sin(a)*6,7);paint('#fff36b',2);}}
}
function aimLen(h){return 170+85*SAVE.up.aim+(h?chAim(h):0);}
function drawAim(h){
  var ox=h.x,oy=h.y-30,sx=Math.sin(h.aim),sy=-Math.cos(h.aim),L=aimLen(h);
  ctx.save();
  // the sighting arc: the full sweep the angle can be fine-tuned across
  ctx.strokeStyle='rgba(255,255,255,.35)';ctx.lineWidth=3;ctx.setLineDash([4,8]);ctx.beginPath();ctx.arc(ox,oy,L,-Math.PI/2-AIM_MAX,-Math.PI/2+AIM_MAX);ctx.stroke();
  ctx.strokeStyle=INK;ctx.lineWidth=8;ctx.setLineDash([10,12]);ctx.beginPath();ctx.moveTo(ox,oy);ctx.lineTo(ox+sx*L,oy+sy*L);ctx.stroke();
  ctx.strokeStyle=h.col;ctx.lineWidth=4;ctx.lineDashOffset=-anim*30;ctx.stroke();ctx.setLineDash([]);
  var rx=ox+sx*L,ry=oy+sy*L,pulse=1+Math.sin(anim*8)*.08;
  ctx.lineWidth=7;ctx.strokeStyle=INK;ctx.beginPath();ctx.arc(rx,ry,15*pulse,0,6.2832);ctx.moveTo(rx-22,ry);ctx.lineTo(rx+22,ry);ctx.moveTo(rx,ry-22);ctx.lineTo(rx,ry+22);ctx.stroke();
  ctx.lineWidth=3.5;ctx.strokeStyle='#ffffff';ctx.stroke();
  if(SAVE.up.aim>0)circ(rx,ry,4,'#ffd23f',2);
  ctx.restore();
}
function drawMeter(h){
  var cx=Math.round(h.x),cy=Math.round(h.y-36),Rr=66,span=1.35,N=40,i;
  var base=h.aim-Math.PI/2;
  ctx.save();
  ctx.lineWidth=18;ctx.strokeStyle=INK;ctx.beginPath();ctx.arc(cx,cy,Rr,base-span-.04,base+span+.04);ctx.stroke();
  ctx.lineWidth=11;
  for(i=0;i<N;i++){
    var u0=-1+2*i/N,u1=-1+2*(i+1)/N,au=Math.abs((u0+u1)/2);
    ctx.strokeStyle=au<0.1?'#37e05a':au<0.3?'#d7ee3a':au<0.6?'#ffa33a':'#ff4d4d';
    ctx.lineCap='butt';ctx.beginPath();ctx.arc(cx,cy,Rr,base+u0*span,base+u1*span+0.02);ctx.stroke();
  }
  ctx.lineCap='round';
  var a=base+h.meter.m*span;
  circ(cx+Math.cos(a)*Rr,cy+Math.sin(a)*Rr,11,'#ffffff',3.5);
  txt((h.id?'ENTER':'SPACE')+' = THROW  (adjust angle: '+(h.id?'LEFT/RIGHT':'A/D')+')',cx,cy-Rr-24,12,'#ffffff','center');
  ctx.restore();
}
function drawRock(o){
  var blink=o.ttl<1.6&&Math.floor(anim*10)%2;
  if(blink)return;
  shadowEll(o.x,o.y+o.h/2-2,o.w/2,8,.3);
  ctx.beginPath();ctx.moveTo(o.x-o.w/2,o.y+o.h/2);ctx.lineTo(o.x-o.w/2+6,o.y-6);ctx.lineTo(o.x-10,o.y-o.h/2);ctx.lineTo(o.x+14,o.y-o.h/2+4);ctx.lineTo(o.x+o.w/2,o.y-4);ctx.lineTo(o.x+o.w/2-4,o.y+o.h/2);ctx.closePath();
  paint('#9a8268',3.5);
  ctx.fillStyle='rgba(255,255,255,.25)';ctx.beginPath();ctx.moveTo(o.x-12,o.y-o.h/2+6);ctx.lineTo(o.x+10,o.y-o.h/2+8);ctx.lineTo(o.x-2,o.y-4);ctx.closePath();ctx.fill();
}
var NEWOBST={umbrella:1,castle:1,banca:1,log:1,stump:1,bush:1,tomb:1,coffin:1,cauldron:1,crab:1,carabao:1,ghost:1};
function drawObst(o){if(o.type==='rock')drawRock(o);else if(NEWOBST[o.type])drawObstNew(o);else drawObstacle(o);}
function drawFire(z){
  var f=clamp(z.t/1,0,1);
  ctx.save();ctx.globalAlpha=f;
  ctx.fillStyle='rgba(255,90,30,.35)';ctx.beginPath();ctx.ellipse(z.x,z.y,z.r,z.r*.5,0,0,6.2832);ctx.fill();
  ctx.lineWidth=3;ctx.strokeStyle='rgba(120,30,10,.6)';ctx.stroke();
  for(var i=0;i<5;i++){
    var fx=z.x-z.r*.6+i*z.r*.3,fh=26+Math.sin(anim*9+i*1.7+z.ph)*9;
    ctx.beginPath();ctx.moveTo(fx-9,z.y+4);ctx.quadraticCurveTo(fx-10,z.y-fh*.5,fx,z.y-fh);ctx.quadraticCurveTo(fx+10,z.y-fh*.5,fx+9,z.y+4);ctx.closePath();paint('#ff6a2a',2.5);
    ctx.beginPath();ctx.moveTo(fx-4,z.y+3);ctx.quadraticCurveTo(fx-5,z.y-fh*.3,fx,z.y-fh*.6);ctx.quadraticCurveTo(fx+5,z.y-fh*.3,fx+4,z.y+3);ctx.closePath();ctx.fillStyle='#ffd23f';ctx.fill();
  }
  ctx.restore();
}
function drawZones(){
  var i;
  for(i=0;i<G.zones.length;i++){
    var z=G.zones[i];
    if(z.type==='fire')drawFire(z);
    else if(z.type==='tide'){
      var tp=Math.min(1,Math.min(z.t,z.t0-z.t)/0.8);
      ctx.save();ctx.globalAlpha=0.75*tp;ctx.fillStyle='rgba(60,170,230,.7)';ctx.beginPath();ctx.ellipse(z.x,z.y,z.r,z.r*.42,0,0,6.2832);ctx.fill();
      ctx.strokeStyle='rgba(235,248,255,.95)';ctx.lineWidth=3;
      for(var wv=0;wv<4;wv++){var wx0=z.x-z.r*.7+wv*z.r*.45,wy0=z.y-8+Math.sin(anim*4+wv+z.ph)*5;ctx.beginPath();ctx.moveTo(wx0,wy0);ctx.quadraticCurveTo(wx0+12,wy0-9,wx0+24,wy0);ctx.stroke();}
      ctx.restore();
    }else if(z.type==='vent'){
      var gl=z.st===1?(0.4+0.6*Math.abs(Math.sin(anim*14))):(z.st===2?1:0.35);
      ctx.fillStyle='#1c1214';ctx.beginPath();ctx.ellipse(z.x,z.y,z.r*.75,z.r*.34,0,0,6.2832);ctx.fill();
      ctx.fillStyle='rgba(255,'+(z.st?110:70)+',30,'+gl+')';ctx.beginPath();ctx.ellipse(z.x,z.y,z.r*.55,z.r*.22,0,0,6.2832);ctx.fill();
      ctx.lineWidth=2.5;ctx.strokeStyle='#2a1c18';ctx.beginPath();ctx.ellipse(z.x,z.y,z.r*.75,z.r*.34,0,0,6.2832);ctx.stroke();
      if(z.st===1)txt('!',z.x,z.y-24,26,'#ffd23f','center');
      if(z.st===2){ctx.fillStyle='rgba(255,180,60,.8)';ctx.fillRect(z.x-8,z.y-90,16,90);}
    }else if(z.type==='rockfall'){
      var p=1-z.t/z.t0;
      ctx.fillStyle='rgba(30,20,10,'+(0.15+p*.3)+')';ctx.beginPath();ctx.ellipse(z.x,z.y+22,16+p*26,6+p*9,0,0,6.2832);ctx.fill();
      var ry=z.y-(1-p)*(z.y-TOP+60);
      ctx.beginPath();ctx.arc(z.x,ry,24,0,6.2832);paint('#9a8268',3.5);
      txt('!',z.x,z.y-10,30,'#ffd23f','center');
    }
  }
  for(i=0;i<G.strikes.length;i++){
    var s=G.strikes[i],q=1-s.t/s.t0;
    ctx.save();ctx.strokeStyle=Math.floor(anim*12)%2?'#fff36b':'#ff5a4a';ctx.lineWidth=4;ctx.setLineDash([8,6]);
    ctx.beginPath();ctx.ellipse(s.x,s.y,56*(1.1-q*.1),24,0,0,6.2832);ctx.stroke();ctx.setLineDash([]);
    ctx.fillStyle='rgba(255,243,107,'+(0.1+q*.3)+')';ctx.fill();ctx.restore();
  }
  if(G.bolt&&G.bolt.t>0){
    var b=G.bolt;ctx.strokeStyle='#ffffff';ctx.lineWidth=7;ctx.beginPath();ctx.moveTo(b.x+10,0);
    for(i=1;i<7;i++)ctx.lineTo(b.x+(i%2?-18:18),i*(b.y/6));ctx.lineTo(b.x,b.y);ctx.stroke();
    ctx.strokeStyle='#fff36b';ctx.lineWidth=3;ctx.stroke();
  }
}

var ARENA=[];
function arenaBG(env,line){return ARENA[env]||(ARENA[env]=buildPixBG(env,line,AW));}
/* ---------- arena world (arena-local coordinates) ---------- */
function drawWorld(){
  var env=G.cfg.env,i;
  bgCanvas=arenaBG(env,G.line);
  ctx.save();
  ctx.beginPath();ctx.rect(AX,0,AW,H);ctx.clip();
  ctx.translate(AX,0);
  ctx.save();
  if(shake>0)ctx.translate(Math.round(rnd(-shake,shake)),Math.round(rnd(-shake,shake)));
  ctx.drawImage(bgCanvas,0,0);
  
  ctx.lineWidth=6;ctx.strokeStyle=INK;ctx.beginPath();ctx.moveTo(0,G.line);ctx.lineTo(AW,G.line);ctx.stroke();
  ctx.lineWidth=4;ctx.strokeStyle='#ffffff';ctx.setLineDash([24,12]);ctx.beginPath();ctx.moveTo(0,G.line);ctx.lineTo(AW,G.line);ctx.stroke();ctx.setLineDash([]);
  txt('SAFE ZONE',12,G.line+20,14,'rgba(255,255,255,.9)','left');
  if(!G.boss){ctx.strokeStyle='rgba(255,255,255,.75)';ctx.lineWidth=4;ctx.setLineDash([10,8]);ctx.beginPath();ctx.ellipse(CAN_X,CAN_Y+4,60,19,0,0,6.2832);ctx.stroke();ctx.setLineDash([]);}
  G.pud.forEach(drawPatch);
  var list=[];
  if(!G.boss)list.push({y:G.can.carried?G.taya.y+1:G.can.y,f:function(){drawCan(G.can);}});
  list.push({y:G.taya.y,f:function(){if(G.boss)drawBoss(G.taya);else drawTaya(G.taya);}});
  G.hitters.forEach(function(h){list.push({y:h.y,f:function(){drawHitter(h);}});});
  G.obst.forEach(function(o){list.push({y:o.y+o.h/2,f:function(){drawObst(o);}});});
  G.pogs.forEach(function(k){list.push({y:k.y,f:function(){drawPogPickup(k);}});});
  G.coins.forEach(function(c){list.push({y:c.y,f:function(){drawCoin(c);}});});
  G.slippers.forEach(function(s){if(s.state!=='fly')list.push({y:s.y,f:function(){drawSlipper(s);}});});
  drawZones();
  drawWeather();
  drawVfxGround();
  drawDecoys();
  drawTansans();
  list.sort(function(a,b){return a.y-b.y;});
  list.forEach(function(e){e.f();});
  G.slippers.forEach(function(s){if(s.state==='fly'||s.state==='rico'){smear(s);drawSlipper(s);}});
  drawVfxTop();
  G.parts.forEach(function(p){ctx.globalAlpha=clamp(1-p.t/p.life,0,1);ctx.fillStyle=p.col;ctx.fillRect(p.x,p.y,p.size,p.size);ctx.globalAlpha=1;});
  G.hitters.forEach(function(h){if(h.meter.on&&h.stun<=0)drawAim(h);if(h.meter.on)drawMeter(h);});
  G.pops.forEach(function(p){txt(p.text,p.x,p.y,24,p.col,'center');});
  if(G.cfg.gust>0){
    ctx.strokeStyle='rgba(255,255,255,.18)';ctx.lineWidth=3;
    for(i=0;i<12;i++){
      var gx=(((i*191+anim*G.gx*2.2)%(AW+200))+(AW+200))%(AW+200)-100,gy=150+((i*83)%430);
      ctx.beginPath();ctx.moveTo(gx,gy);ctx.lineTo(gx+G.gx*0.35,gy+G.gy*0.2);ctx.stroke();
    }
  }
  drawVision();
  ctx.restore();
  drawHUD();
  drawBossHUD();
  ctx.restore();
}
function drawFunnel(cx,cy,hh,w,ph){
  for(var i=0;i<14;i++){
    var u=i/13,y=cy-hh*(1-u),rw=w*(0.15+0.85*(1-u))*(1+Math.sin(ph+i*.8)*.08),ox=Math.sin(ph*1.3+i*.5)*8*(1-u);
    ctx.fillStyle='rgba(190,185,215,'+(0.35+0.25*(1-u))+')';ctx.beginPath();ctx.ellipse(cx+ox,y,rw,rw*.28,0,0,6.2832);ctx.fill();
    ctx.strokeStyle='rgba(60,55,90,.5)';ctx.lineWidth=2;ctx.stroke();
  }
}
function drawWeather(){
  var hz=G.haz,i;
  if(G.flash>0){ctx.fillStyle='rgba(255,255,230,'+clamp(G.flash*2.6,0,.75)+')';ctx.fillRect(0,0,AW,H);}
  if(!hz)return;
  ctx.save();ctx.globalAlpha=clamp(Math.min(hz.dur-hz.t,hz.t)/1.2,0,1);
  var T=hz.type;
  if(T==='TYPHOON'||T==='STORM'){
    ctx.fillStyle=T==='STORM'?'rgba(25,25,60,.38)':'rgba(28,40,90,.32)';ctx.fillRect(0,0,AW,H);
    ctx.strokeStyle='rgba(215,230,255,.6)';ctx.lineWidth=2;
    var wx=T==='TYPHOON'?G.twx:G.twx*2;
    for(i=0;i<110;i++){
      var x=(((i*97.7+anim*wx*3.2)%(AW+120))+(AW+120))%(AW+120)-60,y=(i*137.3+anim*700)%H;
      ctx.beginPath();ctx.moveTo(x,y);ctx.lineTo(x+wx*0.12,y+22);ctx.stroke();
    }
  }else if(T==='FLOOD'){
    ctx.fillStyle='rgba(40,150,215,.34)';ctx.fillRect(0,128,AW,H-128);
    ctx.strokeStyle='rgba(215,240,255,.55)';ctx.lineWidth=3;
    for(i=0;i<26;i++){
      var yy=150+i*21,off=Math.sin(anim*2+i)*18,bx=(i*173)%AW;
      ctx.beginPath();ctx.moveTo(bx+off,yy);ctx.quadraticCurveTo(bx+off+22,yy-8,bx+off+44,yy);ctx.stroke();
    }
    G.hitters.concat([G.taya]).forEach(function(e){ctx.strokeStyle='rgba(235,248,255,.9)';ctx.lineWidth=3;ctx.beginPath();ctx.ellipse(e.x,e.y+4,30+Math.sin(anim*6)*4,10,0,0,6.2832);ctx.stroke();});
  }else if(T==='EARTHQUAKE'){
    ctx.fillStyle='rgba(120,80,40,.16)';ctx.fillRect(0,0,AW,H);
    ctx.strokeStyle='rgba(40,25,10,.55)';ctx.lineWidth=4;
    for(i=0;i<5;i++){var cx=(i*173+40)%AW,cy=200+i*70;ctx.beginPath();ctx.moveTo(cx,cy);ctx.lineTo(cx+26,cy+18);ctx.lineTo(cx+10,cy+36);ctx.lineTo(cx+38,cy+60);ctx.stroke();}
  }else if(T==='LANDSLIDE'){
    ctx.fillStyle='rgba(120,85,45,.2)';ctx.fillRect(0,0,AW,H);
  }else if(T==='FIRE'){
    var p=0.18+0.06*Math.sin(anim*6);
    var gr=ctx.createLinearGradient(0,H,0,H*.4);gr.addColorStop(0,'rgba(255,90,30,'+(p*2)+')');gr.addColorStop(1,'rgba(255,90,30,0)');
    ctx.fillStyle=gr;ctx.fillRect(0,0,AW,H);
  }else if(T==='AMIHAN'){
    ctx.fillStyle='rgba(30,120,170,.2)';ctx.fillRect(0,0,AW,H);
    ctx.strokeStyle='rgba(225,245,255,.7)';ctx.lineWidth=2.5;
    for(i=0;i<70;i++){var ax=(((i*113.7+anim*G.twx*3.4)%(AW+160))+(AW+160))%(AW+160)-80,ay=(i*151.9+anim*40*(1+i%3))%H;ctx.beginPath();ctx.moveTo(ax,ay);ctx.lineTo(ax+G.twx*0.2,ay+G.twy*0.1+6);ctx.stroke();}
  }else if(T==='BROWNOUT'){
    ctx.fillStyle='rgba(14,16,48,.28)';ctx.fillRect(0,0,AW,H);
  }else if(T==='ASHFALL'){
    ctx.fillStyle='rgba(90,70,60,'+(0.12+0.18*(hz.ash||0))+')';ctx.fillRect(0,0,AW,H);
    ctx.fillStyle='rgba(235,230,225,.8)';
    for(i=0;i<90;i++){var sx=(i*89.3+Math.sin(anim*.8+i)*30)%AW,sy=(i*61.7+anim*(50+i%5*14))%H;ctx.fillRect(sx,sy,3,3);}
  }else if(T==='TORNADO'){
    ctx.fillStyle='rgba(60,60,90,.22)';ctx.fillRect(0,0,AW,H);
    drawFunnel(hz.vx,hz.vy+40,230,70,anim*5);
    for(i=0;i<10;i++){var a=anim*5+i*.63,rr=40+(i%4)*14;ctx.fillStyle=i%2?'#d9c28a':'#8fb06a';ctx.fillRect(hz.vx+Math.cos(a)*rr-3,hz.vy-60+Math.sin(a*1.4)*80+i*6,6,6);}
  }
  ctx.restore();
}

/* ---------- brownout / ashfall: darkness with light holes; heroes, lata and glowing tsinelas are redrawn un-tinted on top ---------- */
var VCV=null;
function drawVision(){
  var h=G.haz;if(!h||(h.type!=='BROWNOUT'&&h.type!=='ASHFALL'))return;
  var fade=clamp(Math.min(h.dur-h.t,h.t)/1.2,0,1),bo=h.type==='BROWNOUT';
  if(!VCV){VCV=document.createElement('canvas');VCV.width=AW;VCV.height=H;}
  var g=VCV.getContext('2d'),i,R;
  g.globalCompositeOperation='source-over';g.clearRect(0,0,AW,H);
  g.fillStyle=bo?'rgba(4,5,18,'+(0.93*fade)+')':'rgba(70,58,52,'+((0.55+0.3*(h.ash||0))*fade)+')';g.fillRect(0,0,AW,H);
  g.globalCompositeOperation='destination-out';
  function hole(x,y,r){var rg=g.createRadialGradient(x,y,r*.35,x,y,r);rg.addColorStop(0,'rgba(0,0,0,1)');rg.addColorStop(1,'rgba(0,0,0,0)');g.fillStyle=rg;g.fillRect(x-r,y-r,r*2,r*2);}
  R=bo?118:(430-210*(h.ash||0));
  for(i=0;i<G.hitters.length;i++)hole(G.hitters[i].x,G.hitters[i].y-20,R);
  hole(G.can.x,G.can.y-20,bo?96:R*.7);
  for(i=0;i<G.slippers.length;i++)hole(G.slippers[i].x,G.slippers[i].y-10,bo?42:60);
  for(i=0;i<G.zones.length;i++)if(G.zones[i].type==='vent')hole(G.zones[i].x,G.zones[i].y,70);
  ctx.drawImage(VCV,0,0);
  G.hitters.forEach(function(hh){drawHitter(hh);});
  if(!G.boss)drawCan(G.can);
  G.slippers.forEach(function(s){drawSlipper(s);});
  G.hitters.forEach(function(hh){if(hh.meter.on&&hh.stun<=0)drawAim(hh);if(hh.meter.on)drawMeter(hh);});
  if(bo){ctx.fillStyle='rgba(255,226,120,.9)';for(i=0;i<8;i++){var a=anim*3+i*.8;ctx.fillRect(G.can.x+Math.cos(a)*(26+i*3),G.can.y-24+Math.sin(a*1.3)*(18+i*2),3,3);}}
}
/* ---------- arena HUD ---------- */
function windArrow(x,y,wx){
  var dir=wx>=0?1:-1,len=clamp(Math.abs(wx)*0.5,14,60);
  ctx.strokeStyle=INK;ctx.lineWidth=12;ctx.beginPath();ctx.moveTo(x-dir*len/2,y);ctx.lineTo(x+dir*len/2,y);ctx.stroke();
  ctx.strokeStyle='#d7e6ff';ctx.lineWidth=6;ctx.stroke();
  ctx.beginPath();ctx.moveTo(x+dir*len/2-dir*14,y-12);ctx.lineTo(x+dir*len/2+dir*3,y);ctx.lineTo(x+dir*len/2-dir*14,y+12);ctx.stroke();
}
function drawHUD(){
  var g=ctx.createLinearGradient(0,0,0,70);g.addColorStop(0,'#363c52');g.addColorStop(1,'#1d2130');
  ctx.fillStyle=g;ctx.fillRect(0,0,AW,70);
  ctx.fillStyle='#d8452e';ctx.fillRect(0,68,AW,4);
  ctx.fillStyle=INK;ctx.fillRect(0,72,AW,3);
  RR(8,8,126,54,14,'#ffd23f',3);
  txt(G.endless?'WAVE '+RUN.wave:'LEVEL '+G.level,20,27,22,'#10131c','left',false);
  txt(ENV_NAMES[G.cfg.env],20,49,12,'#7a2a00','left',false);
  txt('CANS',146,16,12,'#ffe0a0','left',false);
  for(var i=0;i<G.need;i++){
    circ(156+i*27,44,10,i<G.hits?'#43e07a':'#434b63',3);
    if(i<G.hits){ctx.strokeStyle='#fff';ctx.lineWidth=3.5;ctx.beginPath();ctx.moveTo(151+i*27,44);ctx.lineTo(155+i*27,49);ctx.lineTo(162+i*27,39);ctx.stroke();}
  }
  var tl=Math.ceil(G.timeLeft),mm=Math.floor(tl/60),ss=tl%60;
  var low=G.timeLeft<15&&!G.clearing,flash=low&&Math.floor(anim*3)%2===0;
  RR(310,8,104,54,14,flash?'#ff4d4d':'#434b63',3);
  txt('TIME',362,17,11,'#ffe0a0','center',false);
  txt(mm+':'+(ss<10?'0':'')+ss,362,42,28,low?'#fff':'#f5c542','center');
  txt('SCORE',428,16,11,'#ffe0a0','left',false);
  txt(String(G.score),428,42,26,G.score>=G.goal?'#7dff7a':'#fff','left');
  txt('/'+G.goal,428+ctx.measureText(String(G.score)).width*0+Math.max(60,String(G.score).length*17),46,13,'#d8d0bc','left',false);
  for(var k=0;k<G.lives;k++){heartPath(578+k*30,20,24);paint('#ff4f6d',2.5);}
  var hov=inRect(mouse,PAUSE_BTN);
  circ(AW-37,34,22,hov?'#ffd23f':'#d8452e',3);
  ctx.fillStyle=INK;ctx.fillRect(AW-46,24,6,20);ctx.fillRect(AW-34,24,6,20);
  // pamato counters
  for(var p=0;p<G.hitters.length;p++){
    var h=G.hitters[p],pw=200,px=G.hitters.length===1?AW/2-pw/2:(p?AW-8-pw:8),py=H-64;
    ctx.globalAlpha=.9;RR(px,py,pw,52,14,'#2b3042',3);RR(px,py,11,52,6,h.col,3);ctx.globalAlpha=1;
    txt(h.name,px+24,py+17,15,h.col,'left');
    ctx.save();ctx.translate(px+84,py+27);slipperShape(0.5,false,1.5);ctx.restore();
    txt('x'+h.ammo,px+104,py+27,24,'#fff','left');
    var al=actLevel(h),an=CHARS[h.char].skills[2].n;
    txt(al<1?ACT_NAME[h.id]+': '+an+' (locked)':(h.acd>0?ACT_NAME[h.id]+': '+an+' '+Math.ceil(h.acd)+'s':ACT_NAME[h.id]+': '+an+' READY'),px+pw/2,py-9,12,al>0&&h.acd<=0?'#7dff7a':'#a8b4c8','center',false);
    if(al>0&&h.acd>0){RR(px,py-4,pw*(1-h.acd/h.acdMax),4,2,'#ffd23f',0);}
    txt(h.meter.on?'SIGHTING '+Math.round(h.aim*57.3)+'deg':(h.id?'ENTER':'SPACE')+' = sight',px+150,py+40,11,h.meter.on?'#ffe66a':'#e8e0cc','center',false);
  }
  if(G.players===1)txt('SOLO: '+pamatoLeft()+' / 3 PAMATO LEFT',AW/2,H-92,13,pamatoLeft()<=1?'#ff9a8a':'#f4ecd6','center');
  if(G.cfg.gust>0||(G.haz&&(G.haz.type==='TYPHOON'||G.haz.type==='STORM'))){
    RR(8,82,132,34,12,'rgba(26,20,66,.85)',3);
    txt('WIND',18,99,12,'#cfe0ff','left',false);
    windArrow(98,99,G.wx);
  }
  if(G.haz){
    var hz=G.haz,c=HAZ[hz.type].col;
    RR(AW/2-150,84,300,28,12,'#1d2130',3);RR(AW/2-146,88,Math.max(0,292*hz.t/hz.dur),20,8,c,0);
    txt(HAZ[hz.type].label+'  '+Math.ceil(hz.t)+'s',AW/2,99,15,'#fff','center');
  }
  for(var t=0;t<G.toasts.length;t++){
    var ts=G.toasts[t];ctx.globalAlpha=Math.min(1,ts.t*2);
    txt(ts.text,AW/2,150+t*32,22,ts.col,'center');ctx.globalAlpha=1;
  }
  if(G.banner&&G.banner.t>0){
    ctx.globalAlpha=Math.min(1,G.banner.t);
    txtT(G.banner.text,AW/2,320,46,'#f5c542','center');
    txt(G.banner.sub,AW/2,382,26,'#fff','center');
    txt('Knock the can down '+G.need+' times  |  Goal '+G.goal,AW/2,424,20,'#f4ecd6','center');
    txt(G.two?'P1: SPACE sight - A/D angle - SPACE throw':'SPACE: sight  -  A/D: fine-tune angle  -  SPACE: throw',AW/2,456,16,'#ffe66a','center');
    if(G.two)txt('P2: ENTER sight - LEFT/RIGHT angle - ENTER throw',AW/2,480,16,'#ffc533','center');
    ctx.globalAlpha=1;
  }
}

/* =========================================================================
   SIDE PILLARS (left + right 20%): animated biome art, weather / disaster scenes,
   combo counters, active pogs, with particle effects driven by the game state
   ========================================================================= */
var PP=[],ppAcc=0;
function ppAdd(o){if(PP.length<360){o.t=0;PP.push(o);}}
function ppUpdate(dt){
  for(var i=0;i<PP.length;i++){var p=PP[i];p.t+=dt;p.x+=p.vx*dt;p.y+=p.vy*dt;p.vy+=(p.g||0)*dt;}
  PP=PP.filter(function(p){return p.t<p.life;});
}
function ppSpawn(dt){
  var hz=G.haz?G.haz.type:(scene==='TIP'&&G.tip?G.tip.type:null),side,n;
  for(side=0;side<2;side++){
    var ox=side?AX+AW:0;
    if(hz==='TYPHOON'||hz==='STORM'||hz==='FLOOD'){
      for(n=0;n<(hz==='STORM'?3:2);n++)ppAdd({x:ox+rnd(0,256),y:rnd(-10,20),vx:(hz==='TYPHOON'?-150:-50),vy:rnd(520,720),life:.7,col:'rgba(200,225,255,.8)',kind:'rain',size:rnd(9,18),oy:20,oh:RH});
    }else if(hz==='EARTHQUAKE'){
      if(Math.random()<dt*14)ppAdd({x:ox+rnd(10,246),y:rnd(RH*.6,RH),vx:rnd(-30,30),vy:rnd(-60,-20),life:.9,col:'rgba(160,125,85,.7)',kind:'dust',size:rnd(8,18)});
    }else if(hz==='LANDSLIDE'){
      if(Math.random()<dt*7)ppAdd({x:ox+rnd(20,230),y:rnd(0,30),vx:rnd(-30,30),vy:rnd(60,140),g:500,life:1.6,col:pick(['#8a6b46','#6f5636','#a5845a']),kind:'rock',size:rnd(6,13)});
    }else if(hz==='FIRE'){
      if(Math.random()<dt*30)ppAdd({x:ox+rnd(20,236),y:RH-rnd(0,50),vx:rnd(-25,25),vy:rnd(-130,-70),g:-20,life:rnd(.6,1.1),col:pick(['#ff5a2a','#ffb02e','#fff07a']),kind:'ember',size:rnd(4,9)});
    }else if(hz==='TORNADO'){
      if(Math.random()<dt*20)ppAdd({x:ox+rnd(40,216),y:RH-rnd(0,40),vx:rnd(-40,40),vy:rnd(-120,-60),life:1.2,col:pick(['#d9c28a','#8fb06a','#ffffff']),kind:'spark',size:rnd(4,8)});
    }else if(Math.random()<dt*2.6){
      var env=ENVART[G.cfg.env];
      ppAdd({x:ox+rnd(10,246),y:rnd(RH+20,H),vx:rnd(-20,20),vy:rnd(-46,-18),life:rnd(2,4),col:[['#7ddc6b','#ffd23f'],['#ffffff','#ffe27a','#7ab8ff'],['#ff5ea8','#35e0d0','#8c7bff'],['#ffffff','#7ad8e8','#ffd23f'],['#7ddc6b','#c9f06a','#e6c27a'],['#b27cff','#7dff7a','#ffffff']][env].sort(function(){return Math.random()-.5;})[0],kind:(env===2||env===5)?'bokeh':'spark',size:rnd(4,9)});
    }
    if(G.mult>1.4&&Math.random()<dt*(G.mult*3))ppAdd({x:ox+rnd(30,226),y:rnd(310,420),vx:rnd(-30,30),vy:rnd(-90,-30),g:60,life:.8,col:pick(['#ffd23f','#7dff7a','#ffffff']),kind:'spark',size:rnd(3,7)});
  }
}
var RH=288;   // height of the disaster / biome viewport
function ppDraw(){
  for(var i=0;i<PP.length;i++){
    var p=PP[i],a=clamp(1-p.t/p.life,0,1);
    ctx.globalAlpha=a;
    if(p.kind==='rain'){ctx.strokeStyle=p.col;ctx.lineWidth=2;ctx.beginPath();ctx.moveTo(p.x,p.y);ctx.lineTo(p.x+p.vx*0.03,p.y+p.size);ctx.stroke();}
    else if(p.kind==='bokeh'){ctx.fillStyle=p.col;ctx.beginPath();ctx.arc(p.x,p.y,p.size,0,6.2832);ctx.fill();}
    else{ctx.fillStyle=p.col;ctx.fillRect(p.x,p.y,p.size,p.size);}
  }
  ctx.globalAlpha=1;
}
function pillarBG(x,env){
  env=ENVART[env];
  var g=ctx.createLinearGradient(0,0,0,H);
  var c=[['#5a2d2a','#2b1a28'],['#1f3f7a','#16224a'],['#2a1d5a','#12151f'],['#2a6a8a','#7a5a2a'],['#1f4a2a','#0f2a18'],['#2a1040','#0a0614']][env];
  g.addColorStop(0,c[0]);g.addColorStop(1,c[1]);ctx.fillStyle=g;ctx.fillRect(x,0,256,H);
  ctx.strokeStyle='rgba(255,255,255,.06)';ctx.lineWidth=2;
  var i;
  if(env===0){for(i=0;i<H;i+=36){ctx.beginPath();ctx.moveTo(x,i);ctx.lineTo(x+256,i);ctx.stroke();for(var j=(i/36%2)*32;j<256;j+=64){ctx.beginPath();ctx.moveTo(x+j,i);ctx.lineTo(x+j,i+36);ctx.stroke();}}}
  else if(env===1){for(i=0;i<H;i+=30){ctx.beginPath();ctx.moveTo(x,i);ctx.lineTo(x+256,i);ctx.stroke();}}
  else if(env===3){for(i=0;i<H;i+=40){ctx.beginPath();ctx.moveTo(x,i);for(var q=0;q<=256;q+=16)ctx.lineTo(x+q,i+Math.sin(anim*2+q*.08+i)*5);ctx.stroke();}}
  else if(env===4){ctx.strokeStyle='rgba(120,200,90,.1)';for(i=0;i<256;i+=44){ctx.beginPath();ctx.moveTo(x+i,0);ctx.bezierCurveTo(x+i+20,200,x+i-20,400,x+i,H);ctx.stroke();}}
  else if(env===5){ctx.fillStyle='rgba(178,124,255,.08)';for(i=0;i<5;i++){ctx.beginPath();ctx.arc(x+128+Math.sin(anim*.7+i)*90,(anim*30+i*150)%H,40,0,6.3);ctx.fill();}}
  else{ctx.strokeStyle='rgba(53,224,208,.12)';for(i=0;i<256;i+=32){ctx.beginPath();ctx.moveTo(x+i,0);ctx.lineTo(x+i,H);ctx.stroke();}
    ctx.fillStyle='rgba(255,94,168,.18)';ctx.fillRect(x,((anim*60)%H),256,3);}
  ctx.fillStyle='rgba(0,0,0,.3)';ctx.fillRect(x===0?252:x,0,4,H);
}
function pBox(x,y,w,h,title,col){
  RR(x,y,w,h,18,'rgba(26,30,44,.9)',3.5);
  if(title){RR(x,y,w,26,14,col||'#d9742a',3);txt(title,x+w/2,y+14,14,'#fff','center',false);}
}
/* the biome silhouette scene */
function drawBiomeScene(x,y,w,h,env){
  env=ENVART[env];
  ctx.save();ctx.beginPath();ctx.rect(x,y,w,h);ctx.clip();
  var tod=(Math.sin(G.time*0.04)+1)/2;
  var g=ctx.createLinearGradient(0,y,0,y+h);
  if(env===2||env===5){g.addColorStop(0,'#1c1646');g.addColorStop(1,'#59337d');}
  else{g.addColorStop(0,tod>.5?'#7ec8ff':'#ffb27a');g.addColorStop(1,tod>.5?'#d6efff':'#ffe0b0');}
  ctx.fillStyle=g;ctx.fillRect(x,y,w,h);
  var sx=x+w*.75,sy=y+40+Math.sin(anim*.5)*3;
  if(env===2||env===5){ctx.fillStyle='#fff7d6';ctx.beginPath();ctx.arc(sx,sy,16,0,6.2832);ctx.fill();}
  else{ctx.fillStyle='#fff2a0';ctx.beginPath();ctx.arc(sx,sy,18,0,6.2832);ctx.fill();ctx.strokeStyle='rgba(255,242,160,.7)';ctx.lineWidth=3;for(var r=0;r<10;r++){var a=anim*.6+r*.628;ctx.beginPath();ctx.moveTo(sx+Math.cos(a)*24,sy+Math.sin(a)*24);ctx.lineTo(sx+Math.cos(a)*32,sy+Math.sin(a)*32);ctx.stroke();}}
  for(var c=0;c<3;c++){var cx=x+((anim*8+c*90)%(w+80))-40,cy=y+30+c*30;ctx.fillStyle=(env===2||env===5)?'rgba(120,100,170,.6)':'rgba(255,255,255,.85)';ctx.beginPath();ctx.ellipse(cx,cy,26,10,0,0,6.2832);ctx.ellipse(cx+16,cy-6,16,9,0,0,6.2832);ctx.fill();}
  var gy=y+h-34,i;
  if(env===0){for(i=0;i<3;i++){var hx=x+8+i*80;RR(hx,gy-56,70,56,5,['#ff7b54','#4ecdc4','#f9d56e'][i],3);ctx.beginPath();ctx.moveTo(hx-5,gy-54);ctx.lineTo(hx+35,gy-84);ctx.lineTo(hx+75,gy-54);ctx.closePath();paint(['#c4412a','#2a9d95','#c9a02d'][i],3);RR(hx+10,gy-40,18,18,3,'#fff1b8',2);}}
  else if(env===1){RR(x+16,gy-70,w-32,70,6,'#f7e7b8',3);RR(x+16,gy-70,w-32,16,6,'#e4572e',3);for(i=0;i<4;i++)RR(x+30+i*50,gy-46,30,26,3,'#7ab8ff',2);ctx.strokeStyle=INK;ctx.lineWidth=3;ctx.beginPath();ctx.moveTo(x+w-30,gy-70);ctx.lineTo(x+w-30,gy-110);ctx.stroke();ctx.fillStyle='#ff4d5e';ctx.beginPath();ctx.moveTo(x+w-30,gy-110);ctx.lineTo(x+w-8+Math.sin(anim*4)*3,gy-102);ctx.lineTo(x+w-30,gy-94);ctx.fill();}
  else if(env===2){for(i=0;i<5;i++){var bh=60+((i*37)%50),bx=x+6+i*47;RR(bx,gy-bh,40,bh,3,['#3b3770','#463f85'][i%2],3);for(var wy=gy-bh+8;wy<gy-8;wy+=14)for(var wx2=bx+6;wx2<bx+34;wx2+=12)if(((wy*7+wx2*3+Math.floor(anim*.7))|0)%5>1){ctx.fillStyle=['#ff5ea8','#35e0d0','#ffd166'][(wx2+wy)%3|0];ctx.fillRect(wx2,wy,7,6);}}}
  else if(env===3){ctx.fillStyle='#3fb6d6';ctx.fillRect(x,gy-44,w,44);ctx.strokeStyle='#fff';ctx.lineWidth=2;for(i=0;i<w;i+=22){ctx.beginPath();ctx.moveTo(x+i,gy-30);ctx.quadraticCurveTo(x+i+6,gy-36,x+i+12,gy-30);ctx.stroke();}
    var pw=Math.sin(anim*1.5)*4;ctx.strokeStyle=INK;ctx.lineWidth=8;ctx.beginPath();ctx.moveTo(x+60,gy);ctx.quadraticCurveTo(x+70,gy-50,x+64+pw,gy-96);ctx.stroke();ctx.strokeStyle='#8a5a2b';ctx.lineWidth=4;ctx.stroke();
    ctx.fillStyle='#3cb371';for(i=0;i<5;i++){ctx.beginPath();ctx.ellipse(x+64+pw+Math.cos(i*1.3)*22,gy-98+Math.sin(i*1.3)*8,22,6,i*1.3,0,6.3);ctx.fill();}
    RR(x+w-90,gy-34,64,14,6,'#ff4d5e',2.5);}
  else if(env===4){for(i=0;i<4;i++){var tx=x+16+i*58,th=70+((i*29)%30);RR(tx,gy-th,16,th,2,'#6a4524',3);circ(tx+8,gy-th,30,['#3d8f45','#2f7a3a'][i%2],3);}}
  else if(env===5){ctx.fillStyle='#2a1c24';ctx.beginPath();ctx.moveTo(x+w*0.5-90,gy);ctx.lineTo(x+w*0.5-12,gy-120);ctx.lineTo(x+w*0.5+12,gy-120);ctx.lineTo(x+w*0.5+90,gy);ctx.closePath();ctx.fill();ctx.lineWidth=3;ctx.strokeStyle=INK;ctx.stroke();
    ctx.fillStyle='#ff7a2a';ctx.beginPath();ctx.moveTo(x+w*0.5-12,gy-120);ctx.lineTo(x+w*0.5+12,gy-120);ctx.lineTo(x+w*0.5+6,gy-96);ctx.lineTo(x+w*0.5-3,gy-108);ctx.closePath();ctx.fill();
    for(i=0;i<3;i++){ctx.fillStyle='rgba(110,96,96,'+(0.6-i*.15)+')';ctx.beginPath();ctx.arc(x+w*0.5+8+i*14+Math.sin(anim+i)*4,gy-130-i*16,12+i*4,0,6.2832);ctx.fill();}
    for(i=0;i<3;i++){var bx=x+((anim*40+i*80)%(w+40))-20,by=y+40+i*22+Math.sin(anim*8+i)*6;ctx.strokeStyle='#120a22';ctx.lineWidth=3;ctx.beginPath();ctx.moveTo(bx-8,by);ctx.quadraticCurveTo(bx-4,by-6+Math.sin(anim*20)*4,bx,by);ctx.quadraticCurveTo(bx+4,by-6+Math.sin(anim*20)*4,bx+8,by);ctx.stroke();}}
  ctx.fillStyle=['#9aa5b5','#e39a4a','#56527f','#e8c88a','#4f7f3a','#2a1a44'][env];ctx.fillRect(x,gy,w,34);ctx.fillStyle=INK;ctx.fillRect(x,gy,w,3);
  ctx.restore();
}
/* the disaster scene */
function drawDisasterScene(x,y,w,h,forced){
  var hz=forced||(G&&G.haz?G.haz.type:(scene==='TIP'&&G&&G.tip?G.tip.type:null));
  if(!hz){drawBiomeScene(x,y,w,h,G.cfg.env);return;}
  ctx.save();ctx.beginPath();ctx.rect(x,y,w,h);ctx.clip();
  var gy=y+h-40,i,u=G&&G.haz&&!forced?1-G.haz.t/G.haz.dur:0.5;
  var sky={TYPHOON:['#2c3a6b','#6a7fb0'],FLOOD:['#4a6f8f','#9cc2d8'],EARTHQUAKE:['#b58b60','#e6c79a'],LANDSLIDE:['#6e6a8a','#c2a67e'],FIRE:['#5a1f1f','#ff8a3a'],TORNADO:['#3a3d4f','#8a8fa6'],STORM:['#1e1c3a','#59557f'],AMIHAN:['#2a6fa0','#9adcf0'],BROWNOUT:['#0a0c20','#232a52'],ASHFALL:['#3a2a2c','#a8806a']}[hz];
  var g=ctx.createLinearGradient(0,y,0,y+h);g.addColorStop(0,sky[0]);g.addColorStop(1,sky[1]);ctx.fillStyle=g;ctx.fillRect(x,y,w,h);
  var cx=x+w/2;
  if(hz==='TYPHOON'){
    for(i=0;i<5;i++){ctx.strokeStyle='rgba(235,240,255,'+(0.5-i*.07)+')';ctx.lineWidth=10-i;ctx.beginPath();ctx.arc(cx,y+70,22+i*16,anim*1.6+i,anim*1.6+i+3.6);ctx.stroke();}
    ctx.fillStyle='#14121c';ctx.beginPath();ctx.arc(cx,y+70,10,0,6.2832);ctx.fill();
    var sw=Math.sin(anim*4)*14+30;ctx.strokeStyle=INK;ctx.lineWidth=7;ctx.beginPath();ctx.moveTo(x+60,gy);ctx.quadraticCurveTo(x+60+sw*.3,gy-60,x+60+sw,gy-100);ctx.stroke();ctx.strokeStyle='#8a5a2b';ctx.lineWidth=4;ctx.stroke();
    ctx.fillStyle='#3cb371';for(i=0;i<5;i++){ctx.beginPath();ctx.ellipse(x+60+sw+Math.cos(i*1.25)*22,gy-100+Math.sin(i*1.25)*10,20,5,i*1.25+sw*.02,0,6.2832);ctx.fill();}
  }else if(hz==='FLOOD'){
    for(i=0;i<3;i++)RR(x+10+i*78,gy-60,66,60,4,['#ff7b54','#4ecdc4','#f9d56e'][i],3);
    var wl=gy-22-Math.sin(u*3.14)*34;
    ctx.fillStyle='rgba(50,150,220,.8)';ctx.beginPath();ctx.moveTo(x,y+h);ctx.lineTo(x,wl);for(var q=0;q<=w;q+=8)ctx.lineTo(x+q,wl+Math.sin(anim*3+q*.1)*4);ctx.lineTo(x+w,y+h);ctx.closePath();ctx.fill();
    RR(x+((anim*30)%(w+30))-20,wl-8,22,10,3,'#8a6b46',2);
  }else if(hz==='EARTHQUAKE'){
    var sh=Math.sin(anim*40)*5;ctx.save();ctx.translate(sh,Math.cos(anim*37)*2);
    RR(cx-45,gy-130,90,130,4,'#d9a066',3);for(i=0;i<3;i++)for(var j=0;j<3;j++)RR(cx-34+j*26,gy-118+i*34,18,22,2,'#fff1b8',2);ctx.restore();
    ctx.strokeStyle=INK;ctx.lineWidth=4;ctx.beginPath();ctx.moveTo(cx-60,gy+4);ctx.lineTo(cx-20,gy+20);ctx.lineTo(cx-34,gy+30);ctx.lineTo(cx+30,gy+34);ctx.stroke();
    if(Math.floor(anim*5)%2)txt('~ ~ ~',cx,y+34,22,'#fff','center');
  }else if(hz==='LANDSLIDE'){
    ctx.fillStyle='#6f8f4a';ctx.beginPath();ctx.moveTo(x,y+80);ctx.lineTo(x+w,gy-10);ctx.lineTo(x+w,gy+40);ctx.lineTo(x,gy+40);ctx.closePath();ctx.fill();ctx.lineWidth=3;ctx.strokeStyle=INK;ctx.stroke();
    var m=(anim*.7)%1;ctx.fillStyle='#8a6b46';ctx.beginPath();ctx.moveTo(x+30+m*120,y+88+m*70);ctx.lineTo(x+90+m*120,y+104+m*70);ctx.lineTo(x+60+m*120,y+128+m*70);ctx.closePath();ctx.fill();ctx.stroke();
    for(i=0;i<5;i++){var t2=(anim*.8+i*.2)%1;circ(x+40+i*34+t2*70,y+80+t2*(h-120)+i*6,7+i%3*2,'#a5845a',2.5);}
  }else if(hz==='FIRE'){
    RR(cx-50,gy-70,100,70,4,'#ffb26b',3);RR(cx-14,gy-34,28,34,2,'#7a4a22',2);
    for(i=0;i<6;i++){var fx=cx-50+i*20,fh=44+Math.sin(anim*10+i*2)*14;ctx.beginPath();ctx.moveTo(fx-11,gy-62);ctx.quadraticCurveTo(fx-12,gy-62-fh*.5,fx,gy-62-fh);ctx.quadraticCurveTo(fx+12,gy-62-fh*.5,fx+11,gy-62);ctx.closePath();paint('#ff6a2a',2.5);ctx.beginPath();ctx.moveTo(fx-5,gy-62);ctx.quadraticCurveTo(fx-6,gy-62-fh*.3,fx,gy-62-fh*.6);ctx.quadraticCurveTo(fx+6,gy-62-fh*.3,fx+5,gy-62);ctx.closePath();ctx.fillStyle='#ffd23f';ctx.fill();}
    for(i=0;i<4;i++){ctx.fillStyle='rgba(60,50,60,'+(0.5-((anim*.6+i*.25)%1)*.5)+')';ctx.beginPath();ctx.arc(cx+Math.sin(anim+i)*20,y+60-((anim*40+i*30)%60),16+i*4,0,6.2832);ctx.fill();}
  }else if(hz==='AMIHAN'){
    for(i=0;i<4;i++){ctx.fillStyle='rgba(40,140,200,'+(0.5+i*.1)+')';ctx.beginPath();ctx.moveTo(x,y+h);ctx.lineTo(x,gy-18-i*10);for(var q2=0;q2<=w;q2+=8)ctx.lineTo(x+q2,gy-18-i*10+Math.sin(anim*3+q2*.09+i)*6);ctx.lineTo(x+w,y+h);ctx.closePath();ctx.fill();}
    RR(x+10,gy-26,w-20,10,2,'#9a6a38',2.5);for(i=0;i<6;i++)RR(x+20+i*44,gy-26,6,26,1,'#6a4020',2);
    ctx.strokeStyle='rgba(235,248,255,.8)';ctx.lineWidth=3;for(i=0;i<8;i++){var wx2=(x+((anim*140+i*60)%(w+80))-40),wy2=y+30+i*14;ctx.beginPath();ctx.moveTo(wx2,wy2);ctx.lineTo(wx2+34,wy2+3);ctx.stroke();}
  }else if(hz==='BROWNOUT'){
    for(i=0;i<5;i++){var bh2=60+((i*37)%50);ctx.fillStyle='#12142e';ctx.fillRect(x+6+i*47,gy-bh2,40,bh2);for(var wy3=gy-bh2+8;wy3<gy-8;wy3+=14)for(var wx3=x+12+i*47;wx3<x+40+i*47;wx3+=14){ctx.fillStyle=(Math.sin(anim*7+wx3*wy3)>0.2)?'#ffd86a':'#1d2040';ctx.fillRect(wx3,wy3,8,8);}}
    var rg2=ctx.createRadialGradient(cx,gy-16,6,cx,gy-16,70);rg2.addColorStop(0,'rgba(255,230,140,.9)');rg2.addColorStop(1,'rgba(255,230,140,0)');ctx.fillStyle=rg2;ctx.fillRect(x,y,w,h);
    drawCan({x:cx,y:gy+4,state:'up',fall:0,env:3});
  }else if(hz==='ASHFALL'){
    ctx.fillStyle='#2a1c1c';ctx.beginPath();ctx.moveTo(cx-120,gy);ctx.lineTo(cx-16,y+60);ctx.lineTo(cx+16,y+60);ctx.lineTo(cx+120,gy);ctx.closePath();ctx.fill();ctx.lineWidth=3;ctx.strokeStyle=INK;ctx.stroke();
    ctx.fillStyle='#ff7a2a';ctx.beginPath();ctx.moveTo(cx-16,y+60);ctx.lineTo(cx+16,y+60);ctx.lineTo(cx+9,y+92);ctx.lineTo(cx-4,y+76);ctx.closePath();ctx.fill();
    for(i=0;i<4;i++){ctx.fillStyle='rgba(90,76,72,'+(0.6-((anim*.5+i*.25)%1)*.5)+')';ctx.beginPath();ctx.arc(cx+Math.sin(anim+i)*18,y+50-((anim*34+i*24)%70),18+i*5,0,6.2832);ctx.fill();}
    ctx.fillStyle='rgba(240,235,228,.85)';for(i=0;i<40;i++)ctx.fillRect(x+(i*37)%w,y+(i*53+anim*60)%h,3,3);
    var ev=(Math.sin(anim*3)>0)?1:0;if(ev){ctx.fillStyle='#ffb02e';ctx.fillRect(x+40,gy-40,14,40);ctx.fillRect(x+w-60,gy-60,14,60);}
  }else if(hz==='TORNADO'){
    drawFunnel(cx+Math.sin(anim*.8)*40,gy,h-70,58,anim*5);
    for(i=0;i<8;i++){var a=anim*6+i*.8;ctx.fillStyle=i%2?'#d9c28a':'#8fb06a';ctx.fillRect(cx+Math.sin(anim*.8)*40+Math.cos(a)*(30+i*5),gy-30-i*20+Math.sin(a)*8,6,6);}
  }else{
    ctx.fillStyle='#3c3a64';for(i=0;i<4;i++){ctx.beginPath();ctx.ellipse(x+40+i*55,y+40+(i%2)*14,38,20,0,0,6.2832);ctx.fill();}
    var ph=Math.floor(anim*2.2)%3;
    if(ph===0){ctx.strokeStyle='#fff7a0';ctx.lineWidth=6;ctx.beginPath();ctx.moveTo(cx+10,y+50);ctx.lineTo(cx-14,y+100);ctx.lineTo(cx+8,y+104);ctx.lineTo(cx-18,y+h-50);ctx.stroke();ctx.fillStyle='rgba(255,255,220,.3)';ctx.fillRect(x,y,w,h);}
  }
  if(hz!=='TORNADO'&&hz!=='EARTHQUAKE'){ctx.fillStyle=hz==='FLOOD'?'rgba(0,0,0,0)':'rgba(30,25,50,.55)';ctx.fillRect(x,gy+30,w,10);}
  ctx.restore();
}
function pogTimerRow(h,type,t,x,y){
  var d=POGS[type],frac=d.dur?clamp(t/d.dur,0,1):1;
  drawPog(type,x+16,y+16,15,0);
  ctx.strokeStyle='#ffffff';ctx.lineWidth=4;ctx.beginPath();ctx.arc(x+16,y+16,19,-Math.PI/2,-Math.PI/2+6.2832*frac);ctx.stroke();
  txt(d.short,x+42,y+10,12,'#fff','left',false);
  txt(d.dur?Math.ceil(t)+'s':'READY',x+42,y+25,13,d.bg,'left',false);
}
function drawPillars(){
  var env=G.cfg.env,L=0,R=AX+AW,i;
  pillarBG(L,env);pillarBG(R,env);
  ctx.save();
  // ---- LEFT: disaster / weather scene, score + combo ----
  var hz=G.haz?G.haz.type:(scene==='TIP'&&G.tip?G.tip.type:null);
  pBox(8,8,240,RH+34,hz?HAZ[hz].label+' ACTIVE':'WEATHER REPORT',hz?HAZ[hz].col:'#2fa7d9');
  ctx.save();if(hz==='EARTHQUAKE')ctx.translate(Math.sin(anim*45)*2,0);
  drawDisasterScene(14,38,228,RH);ctx.restore();
  if(G.haz){RR(14,38+RH-18,228,12,6,'#171b28',2);RR(16,38+RH-16,Math.max(0,224*G.haz.t/G.haz.dur),8,4,HAZ[hz].col,0);}
  else txt('ALL CLEAR - STAY ALERT',128,38+RH-12,13,'#fff','center');
  if(hz&&scene==='TIP'&&Math.floor(anim*4)%2){ctx.strokeStyle=HAZ[hz].col;ctx.lineWidth=5;rrect(8,8,240,RH+34,18);ctx.stroke();}
  pBox(8,338,240,150,'SCORE & COMBO','#d8452e');
  txt(String(G.score),128,382,44,G.score>=G.goal?'#7dff7a':'#ffffff','center');
  RR(24,408,208,12,6,'#171b28',2.5);RR(26,410,Math.min(204,204*G.score/Math.max(1,G.goal)),8,4,G.score>=G.goal?'#43e07a':'#ffd23f',0);
  txt('GOAL '+G.goal,128,432,13,'#d8d0bc','center',false);
  var cb=G.mult>1;
  txt('COMBO x'+G.mult.toFixed(2),30,458,22,cb?'#7dff7a':'#d3d0ee','left');
  txt('STREAK '+G.streak,30,478,15,G.streak>1?'#ffd23f':'#a9a5d8','left',false);
  if(G.two)txt('SYNC x'+G.syncs,226,478,15,'#f5c542','right',false);
  pBox(8,498,240,104,'COINS','#d98a00');
  coinIcon(40,548,17);txt(String(SAVE.coins),66,548,34,'#ffe27a','left');
  txt('+'+G.coinsLvl+' this level',30,584,15,'#ffe66a','left',false);
  pBox(8,612,240,100,SAVE.name,'#d9742a');
  txt(G.two?'2-PLAYER CO-OP':'SOLO',128,654,18,'#ffffff','center');
  txt('Stars '+totalStars()+'/150',128,680,14,'#ffe66a','center',false);
  txt('Best '+totalScore(SAVE),128,698,13,'#e8e0cc','center',false);
  // ---- RIGHT: biome art, active pogs, mode rules, hazard briefing ----
  var rx=R+8;
  pBox(rx,8,240,226,BIOMES[env].name+' BIOME',BIOMES[env].col);
  drawBiomeScene(rx+6,38,228,190,env);
  pBox(rx,244,240,208,'MYSTERY EFFECTS','#e8457f');
  var any=false,row=0;
  for(i=0;i<G.hitters.length;i++){
    var h=G.hitters[i],ry=300+i*78;
    txt(h.name,rx+14,ry-12,14,h.col,'left',false);
    var col=0,effs=[];
    if(h.ghost)effs.push(['GHOST',1]);if(h.boost>0)effs.push(['SPEED',h.boost]);if(h.multi>0)effs.push(['MULTI',h.multi]);if(h.dbl>0)effs.push(['COINS',h.dbl]);if(h.mud>0)effs.push(['MUD',h.mud]);if(h.rev>0)effs.push(['REVERSE',h.rev]);if(h.jit>0)effs.push(['JITTER',h.jit]);
    for(var e=0;e<effs.length&&e<4;e++){pogTimerRow(h,effs[e][0],effs[e][1],rx+10+(e%2)*116,ry+Math.floor(e/2)*36);any=true;}
    if(!effs.length)txt('none',rx+50,ry-12,12,'#8a94aa','left',false);
  }
  if(!any)txt(G.rage>0?'TAYA RAGE '+Math.ceil(G.rage)+'s!':'Named pog = known effect. ? = gamble!',rx+120,440,13,G.rage>0?'#ff6a6a':'#e8e0cc','center',false);
  pBox(rx,462,240,120,G.two?'CO-OP RULES':'SOLO RULES','#2a9d6a');
  if(G.two){
    txt('4 shared lives  |  goal '+G.goal,rx+120,500,13,'#fff','center',false);
    txt('Taya reacts 30% faster',rx+120,520,13,'#ffe0a0','center',false);
    txt('Pincer: both out = +10% speed',rx+120,540,13,'#ffe0a0','center',false);
    txt('Retrieve together: +100 SYNC',rx+120,560,13,'#f5c542','center',false);
  }else{
    txt('Only 3 pamato - no spares!',rx+120,500,14,'#fff','center',false);
    txt('Pamato left: '+pamatoLeft(),rx+120,526,22,pamatoLeft()<=1?'#ff9a8a':'#7dff7a','center');
    txt('0 left = LEVEL FAILED',rx+120,556,13,'#ffe0a0','center',false);
  }
  pBox(rx,592,240,120,'SDG 13 CLIMATE ACTION','#2a62c9');
  var tip=hz?HAZ[hz].hazard:'Disasters hit every 15-25 seconds. Stay alert!';
  var ls=wrap(tip,214,12);for(i=0;i<ls.length&&i<4;i++)txt(ls[i],rx+120,628+i*17,12,'#e8eeff','center',false);
  ppDraw();
  ctx.restore();
  // dark divider at the arena edges
  ctx.fillStyle=INK;ctx.fillRect(AX-4,0,4,H);ctx.fillRect(AX+AW,0,4,H);
}

/* ---------- main loop ---------- */
var lastT=performance.now();
function inGame(){return scene==='PLAY'||scene==='BRIEF'||scene==='PAUSE'||scene==='TIP'||scene==='LEVEL_CLEAR'||scene==='LEVEL_FAILED';}
function frame(now){
  var dt=Math.min(0.05,(now-lastT)/1000);lastT=now;
  anim+=dt;
  try{
    if(selMsgT>0)selMsgT-=dt;
    if(shopMsgT>0)shopMsgT-=dt;
    if(profMsgT>0)profMsgT-=dt;
    slideX+=(biome*W-slideX)*Math.min(1,dt*9);
    if(scene==='LOADING')ldUpdate(dt);
    introUpdate(dt);
    if(scene==='PLAY')updatePlay(dt);
    else if(scene==='BRIEF'){G.brief.age+=dt;}
    else if(scene==='LEVEL_CLEAR'||scene==='UPGRADE'){celebrateUpdate(dt);}
    if(G&&inGame()){ppSpawn(scene==='PAUSE'?0:dt);ppUpdate(dt);}
    render();
  }catch(err){console.error(err);}
  requestAnimationFrame(frame);
}
bus.on('canHit',function(){if(!G)return;for(var i=0;i<26;i++){var side=Math.random()<.5?0:AX+AW;ppAdd({x:side+rnd(10,246),y:rnd(300,420),vx:rnd(-80,80),vy:rnd(-160,-40),g:300,life:1,col:pick(['#ffd23f','#7dff7a','#d8452e','#f5c542']),kind:'spark',size:rnd(4,8)});}});
bus.on('levelStart',function(){PP=[];});
function render(){
  ctx.setTransform(PXS,0,0,PXS,0,0);ctx.globalAlpha=1;renderScene();present();
}
function renderScene(){
  ctx.fillStyle='#12151f';ctx.fillRect(0,0,W,H);
  switch(scene){
    case 'LOADING':drawLoading();return;
    case 'MENU':drawMenu();return;
    case 'STORY':drawStory();return;
    case 'OUTRO':drawOutro();return;
    case 'MODE':drawMode();return;
    case 'HOWTO':drawHowto();return;
    case 'PROFILES':drawProfiles();return;
    case 'NAME':drawName();return;
    case 'RULES':drawRules();return;
    case 'CHARS':drawChars();return;
    case 'CAT_POG':drawCatPog();return;
    case 'CAT_DIS':drawCatDis();return;
    case 'SELECT':drawSelect();return;
    case 'SHOP':drawShop();return;
    case 'BOARD':drawBoardScreen();return;
    case 'SURVIVAL':drawSurvival();return;
    case 'UPGRADE':drawUpgrade();return;
  }
  drawPillars();drawWorld();drawOverlay();if(G&&G.tut&&scene==='PLAY')drawTut();
}

/* ---------- intro: Bisdak Studio splash + real loading progress ---------- */
var LD={t:0,target:0,disp:0,msg:'Waking up the barangay...',ready:false,stepI:0,steps:[],end:0,boot:null,spark:[]};
function ldSet(p,m){if(p>LD.target)LD.target=p;if(m)LD.msg=m;}
(function(){var rg=mulberry(77);for(var i=0;i<70;i++)LD.spark.push({x:rg()*W,y:rg()*H,r:1+rg()*2.4,ph:rg()*6});})();
function ldUpdate(dt){
  LD.t+=dt;
  if(LD.ready&&LD.stepI<LD.steps.length){
    LD.steps[LD.stepI]();LD.stepI++;
    ldSet(0.55+0.45*LD.stepI/LD.steps.length,LD.stepI<LD.steps.length?'Painting the '+BIOMES[Math.min(NB-1,LD.stepI)].name.toLowerCase()+' biome...':'Ready to play!');
  }
  var cap=Math.min(LD.target,LD.t/3.0);          // the splash always plays for at least ~3 seconds
  LD.disp+=(cap-LD.disp)*Math.min(1,dt*6);
  if(LD.disp>=0.985&&LD.ready&&LD.stepI>=LD.steps.length&&LD.t>3.3){
    LD.end+=dt;
    if(LD.end>0.5&&LD.boot){var f=LD.boot;LD.boot=null;f();}
  }
}
function drawLoading(){
  var t=LD.t,i,g=ctx.createLinearGradient(0,0,0,H);
  g.addColorStop(0,'#2a1006');g.addColorStop(.6,'#5a2a12');g.addColorStop(1,'#8a4a1e');
  ctx.fillStyle=g;ctx.fillRect(0,0,W,H);
  for(i=0;i<LD.spark.length;i++){var s=LD.spark[i],tw=.4+.6*Math.abs(Math.sin(t*1.6+s.ph));ctx.globalAlpha=tw*.8;ctx.fillStyle='#fff';ctx.beginPath();ctx.arc(s.x,s.y,s.r,0,6.2832);ctx.fill();}
  ctx.globalAlpha=1;
  var fadeOut=LD.end>0?clamp(1-LD.end/0.5,0,1):1;
  ctx.save();ctx.globalAlpha=fadeOut;
  // studio badge: spinning sun rays, a bold B and a pamato tucked underneath
  var a=clamp(t/0.8,0,1),cx=W/2,cy=190,pop=1+Math.max(0,1-t*2.2)*.5;
  ctx.save();ctx.globalAlpha=fadeOut*a;ctx.translate(cx,cy);ctx.scale(pop,pop);
  ctx.strokeStyle='#ffd23f';ctx.lineWidth=7;
  for(i=0;i<16;i++){var an=t*.5+i*0.3927;ctx.beginPath();ctx.moveTo(Math.cos(an)*74,Math.sin(an)*74);ctx.lineTo(Math.cos(an)*(92+(i%2)*14),Math.sin(an)*(92+(i%2)*14));ctx.stroke();}
  var bg=ctx.createRadialGradient(-20,-24,10,0,0,70);bg.addColorStop(0,'#fff09a');bg.addColorStop(1,'#ff9a2e');
  circ(0,0,66,bg,6);
  ctx.font='900 96px '+FONT;ctx.textAlign='center';ctx.textBaseline='middle';ctx.lineWidth=9;ctx.strokeStyle=INK;ctx.strokeText('B',0,-6);ctx.fillStyle='#ffffff';ctx.fillText('B',0,-6);
  ctx.save();ctx.translate(0,44);slipperShape(Math.PI/2,false,1.9,SKINS.TSINELAS);ctx.restore();
  ctx.restore();
  // BISDAK STUDIO - letters drop in one by one
  var name='BISDAK STUDIO',sp=50,x0=cx-(name.length-1)*sp/2;
  for(i=0;i<name.length;i++){
    var ti=t-0.5-i*0.09;if(ti<0||name.charAt(i)===' ')continue;
    var p=clamp(ti/0.35,0,1),e=1-Math.pow(1-p,3);
    ctx.globalAlpha=fadeOut*p;txt(name.charAt(i),x0+i*sp,320+(1-e)*-46,68,i<6?'#ffd23f':'#f5c542','center');
  }
  ctx.globalAlpha=fadeOut;
  if(t>1.9)txt('PRESENTS',cx,376,22,'rgba(255,255,255,'+clamp((t-1.9)*1.5,0,1)+')','center',false);
  if(t>2.2){var tp=clamp((t-2.2)/0.6,0,1);ctx.save();ctx.globalAlpha=fadeOut*tp;ctx.translate(cx,446);ctx.scale(.8+.2*tp,.8+.2*tp);txt('STRIKE D LATA',0,0,76,'#ffd23f','center');ctx.restore();}
  // progress bar with a pamato sliding along it
  var bx=cx-330,by=548,bw=660,bh=30,pr=clamp(LD.disp,0,1),barA=clamp((t-0.8)/0.5,0,1);
  ctx.globalAlpha=fadeOut*barA;
  RR(bx,by,bw,bh,15,'#171b28',4);
  if(pr>0.01){var fg=ctx.createLinearGradient(bx,0,bx+bw,0);fg.addColorStop(0,'#d8452e');fg.addColorStop(1,'#ffd23f');RR(bx+4,by+4,Math.max(18,(bw-8)*pr),bh-8,11,fg,0);}
  var sx=bx+10+(bw-20)*pr,sy=by+bh/2+Math.sin(t*14)*3;
  for(i=0;i<6;i++){ctx.globalAlpha=fadeOut*barA*(1-i/6)*.6;ctx.fillStyle='#fff';ctx.fillRect(sx-30-i*13,by+bh+2+Math.sin(t*9+i)*3,6,6);}
  ctx.globalAlpha=fadeOut*barA;
  ctx.save();ctx.translate(sx,sy-6);slipperShape(Math.PI/2+Math.sin(t*14)*.12,false,2.3,SKINS.TSINELAS);ctx.restore();
  txt(Math.floor(pr*100)+'%',cx,by+62,24,'#ffffff','center');
  txt(loadError||(pr<0.22?'Loading level table...':pr<0.4?'Loading SDG 13 safety tips...':pr<0.55?'Loading player profiles...':pr<0.97?'Painting the biomes...':'Ready to play!'),cx,by+92,16,loadError?'#ff9a8a':'#e8e0cc','center',false);
  ctx.restore();
}

/* ---------- boot: level table, tips and profiles come from the Java server ---------- */
ldSet(0.04,'Contacting the Java server...');
var ld1=fetch('/api/levels').then(function(r){return r.json();}).then(function(v){LEVELS=v;ldSet(0.22,'Level table loaded (60 levels)');return v;});
var ld2=fetch('/api/tips').then(function(r){return r.json();}).then(function(v){TIPS=v;ldSet(0.36,'SDG 13 safety tips loaded');return v;});
var ld3=fetch('/api/save').then(function(r){return r.json();}).then(function(v){ldSet(0.5,'Player profiles loaded');return v;});
var ldF=Promise.all([document.fonts.load('20px "Jersey 10"'),document.fonts.load('20px "Press Start 2P"')]).catch(function(){});
Promise.all([ld1,ld2,ld3,ldF]).then(function(a){
  var s=a[2];
  if(!s||(!s.profiles&&!s.stars)){try{var ls=localStorage.getItem('sdl_db_v4');if(ls)s=JSON.parse(ls);}catch(e){}}
  DB=normDB(s);
  loadBoard();
  for(var e=0;e<NB;e++)(function(env){LD.steps.push(function(){bgFor(env);arenaBG(env,LEVELS[env*10].lineY);});})(e);
  LD.boot=function(){
    introAfter=function(){
      SAVE=normSave(null);PROF=null;scene='MENU';sel=0;   // guest view until a profile is chosen on the homepage
      cv.focus();
    };
    startStory();cv.focus();
  };
  LD.ready=true;ldSet(0.55,'Painting the street biome...');
}).catch(function(e){loadError='Start the game with: java StrikeDLata';console.error(e);});
requestAnimationFrame(frame);
window.__sdl={get:function(){return G;},scene:function(){return scene;},save:function(){return SAVE;},db:function(){return DB;},levels:function(){return LEVELS;},btns:function(){return buttonsFor(scene);},
  sel:function(){return selLevel;},board:function(){return BOARD;},keys:keys,setPlayers:function(n){setPlayers(n);},ld:function(){return [LD.t,LD.disp,LD.ready];},trigger:function(t){triggerHazard(t);},run:function(){return RUN;},begin:function(){ackBrief();},ack:function(){ackBrief();},start:function(n,p){players=p;startLevel(n);},unlockAll:function(){ALL_UNLOCKED=true;},pog:function(i,t){applyPog(G.hitters[i],t);},biomeOpen:function(b){return biomeOpen(b);},go:function(s){scene=s;sel=0;},hit:function(){var h=G.hitters[0];hitCan({owner:0,x:h.x,y:h.y,state:'air',u:1,z:10});}};
})();

""";

    private static final String FONT_BODY = "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";

    private static final String FONT_TITLE = "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";

}
