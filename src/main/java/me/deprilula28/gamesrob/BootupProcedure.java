package me.deprilula28.gamesrob;

import com.github.kevinsawicki.http.HttpRequest;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import me.deprilula28.gamesrob.baseFramework.GameState;
import me.deprilula28.gamesrob.baseFramework.GameType;
import me.deprilula28.gamesrob.baseFramework.Match;
import me.deprilula28.gamesrob.commands.CommandManager;
import me.deprilula28.gamesrob.data.GuildProfile;
import me.deprilula28.gamesrob.data.SQLDatabaseManager;
import me.deprilula28.gamesrob.data.UserProfile;
import me.deprilula28.gamesrob.utility.Cache;
import me.deprilula28.gamesrob.utility.Constants;
import me.deprilula28.gamesrob.utility.Log;
import me.deprilula28.gamesrob.utility.Utility;
import me.deprilula28.gamesrob.website.WebhookHandlers;
import me.deprilula28.gamesrob.website.Website;
import me.deprilula28.jdacmdframework.Command;
import me.deprilula28.jdacmdframework.CommandFramework;
import me.deprilula28.jdacmdframework.Settings;
import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDABuilder;
import net.dv8tion.jda.core.OnlineStatus;
import net.dv8tion.jda.core.entities.Game;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;

import java.io.File;
import java.io.FileReader;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class BootupProcedure {
    public static void bootup(String[] args) {
        long begin = System.currentTimeMillis();
        List<String> argList = Arrays.asList(args);
        task(argList, "Loading arguments", loadArguments, false);
        task(argList, "Loading languages", n -> Language.loadLanguages(), false);
        task(argList, "Connecting to Discord", connectDiscord, false);
        task(argList, "Loading framework", frameworkLoad, true);
        task(argList, "Loading data", loadData, true);
        task(argList, "Loading DiscordBotsOrg", dblLoad, false);
        task(argList, "Loading website", loadWebsite, false);
        task(argList, "Loading presence task", presenceTask, false);
        task(argList, "Transferring data to DB", transferToDb, GamesROB.database.isPresent());
        Log.info("Bot fully loaded in " + Utility.formatPeriod(System.currentTimeMillis() - begin) + "!");
    }

    @FunctionalInterface
    private static interface BootupTask {
        void execute(List<String> args) throws Exception;
    }

    private static String token;
    public static Optional<String> optDblToken;
    private static int shardCount;
    private static int port;
    public static String secret;

    private static final BootupTask loadArguments = args -> {
        List<Optional<String>> pargs = Utility.matchValues(args, "token", "dblToken", "shards", "ownerID",
                "sqlDatabase", "debug", "twitchUserID", "port", "clientSecret", "twitchClientID");
        token = pargs.get(0).orElseThrow(() -> new RuntimeException("You need to provide a token!"));
        optDblToken = pargs.get(1);
        shardCount = pargs.get(2).map(Integer::parseInt).orElse(1);
        GamesROB.owners = pargs.get(3).map(it -> Arrays.stream(it.split(",")).map(Long::parseLong).collect(Collectors.toList()))
                .orElse(Collections.singletonList(197448151064379393L));
        GamesROB.database = pargs.get(4).map(SQLDatabaseManager::new);
        GamesROB.debug = pargs.get(5).map(Boolean::parseBoolean).orElse(false);
        GamesROB.twitchUserIDListen = pargs.get(6).map(Long::parseLong).orElse(-1L);
        port = pargs.get(7).map(Integer::parseInt).orElse(80);
        secret = pargs.get(8).orElse("");
        GamesROB.twitchClientID = pargs.get(9);
    };

    private static final BootupTask transferToDb = args -> {
        GamesROB.database.ifPresent(db -> {
            int transfered = 0;
            for (File file : Constants.GUILDS_FOLDER.listFiles()) {
                FileReader reader = null;
                try {
                    reader = new FileReader(new File(file, "leaderboard.json"));
                    GuildProfile guildProfile = Constants.GSON.fromJson(reader, GuildProfile.class);
                    GuildProfile.manager.saveToSQL(db, guildProfile);
                    transfered ++;
                    Log.info("Transferred " + file.getName() + ". (" + transfered + ")");
                    reader.close();
                } catch (Exception e) {
                    if (reader != null) Utility.quietlyClose(reader);
                    Log.info("Failed to save " + file.getAbsolutePath() + ": " + e.getClass().getName() +  ": "
                            + e.getMessage());
                }
            }
            Log.info(transfered + " guilds transferred");
            transfered = 0;

            for (File file : Constants.USER_PROFILES_FOLDER.listFiles()) {
                FileReader reader = null;
                try {
                    reader = new FileReader(file);
                    UserProfile userProfile = Constants.GSON.fromJson(reader, UserProfile.class);
                    UserProfile.manager.saveToSQL(db, userProfile);
                    transfered ++;
                    Log.info("Transferred " + file.getName() + ". (" + transfered + ")");
                    reader.close();
                } catch (Exception e) {
                    if (reader != null) Utility.quietlyClose(reader);
                    Log.info("Failed to save " + file.getAbsolutePath() + ": " + e.getClass().getName() +  ": "
                            + e.getMessage());
                }
            }
            Log.info(transfered + " users transferred");
        });
    };

    private static final BootupTask connectDiscord = args -> {
        int curShard = 0;
        while (curShard < shardCount) {
            String shard = curShard + "/" + (shardCount - 1);

            JDA jda = new JDABuilder(AccountType.BOT).setToken(token)
                    .useSharding(curShard, shardCount)
                    .setStatus(OnlineStatus.DO_NOT_DISTURB).setGame(Game.watching("it all load... (" + shard + ")"))
                    .buildBlocking();
            GamesROB.shards.add(jda);
            Match.ACTIVE_GAMES.put(jda, new ArrayList<>());

            Log.info("Shard loaded: " + shard);
            curShard ++;
            if (curShard < shardCount) Thread.sleep(5000L);
        }
    };

    private static final BootupTask frameworkLoad = args -> {
        CommandFramework f = new CommandFramework(GamesROB.shards, Settings.builder()
                .loggerFunction(Log::info).removeCommandMessages(false).protectMentionEveryone(true)
                .async(true).threadPool(new ThreadPoolExecutor(10, 100, 5, TimeUnit.MINUTES,
                        new LinkedBlockingQueue<>())).prefixGetter(Constants::getPrefix).joinQuotedArgs(true)
                .commandExceptionFunction((context, exception) -> {
                    context.send("⛔ An error has occured! It has been reported to devs. My bad...");
                    Log.exception("Command: " + context.getMessage().getRawContent(), exception, context);
                }).genericExceptionFunction((message, exception) -> Log.exception(message, exception))
                .build());
        GamesROB.commandFramework = f;

        // Commands
        CommandManager.registerCommands(f);

        // Games
        Arrays.stream(GamesROB.ALL_GAMES).forEach(cur -> {
            Command command = f.command(cur.getAliases(), Match.createCommand(cur));
            if (cur.getGameType() == GameType.MULTIPLAYER) command.setUsage(command.getName().toLowerCase() + " <Players>");
        });

        f.handleEvent(MessageReceivedEvent.class, event -> {
            if (Match.PLAYING.containsKey(event.getAuthor())) {
                Match game = Match.PLAYING.get(event.getAuthor());

                if (game.getGameState() == GameState.MATCH)
                    try {
                        if (event.getGuild() == null) game.getMatchHandler().receivedDM(event.getMessage().getRawContent(),
                                event.getAuthor(), event.getMessage());
                        else game.getMatchHandler().receivedMessage(event.getMessage().getRawContent(),
                                    event.getAuthor(), event.getMessage());
                    } catch (Exception e) {
                        Log.exception("Game of " + game.getGame().getName(Constants.DEFAULT_LANGUAGE) + " had an error", e);
                        game.onEnd("⛔ An error occurred causing the game to end.\nMy bad :c", false);
                    }
            }
        });
    };

    private static final BootupTask loadData = args -> {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("Shutting down...");

            /*
            if (GamesROB.twitchUserIDListen != -1 && WebhookHandlers.hasConfirmed && GamesROB.twitchClientID.isPresent())
                Log.trace("Response to twitch de-subscription: " +
                    HttpRequest.post(String.format("https://api.twitch.tv/helix/webhooks/hub?hub.callback=%s&hub.mode=%s&hub.topic=%s",
                            "http%3A%2F%2F1%2Ftwitchwebhook", "unsubscribe",
                            "https%3A%2F%2Fapi.twitch.tv%2Fhelix%2Fstreams%3Fuser_id%3D" + GamesROB.twitchUserIDListen))
                    .header("Accept", "application/vnd.twitchtv.v5+json")
                    .header("Client-ID",  GamesROB.twitchClientID.get())
                    .body());

            Cache.onClose();
                    */
        }));

        GamesROB.database.ifPresent(SQLDatabaseManager::registerTables);
    };

    private static final BootupTask dblLoad = args ->
        optDblToken.ifPresent(dblToken -> {
            GamesROB.dboAPI = Optional.of(GamesROB.commandFramework.setupDiscordBotsOrg(dblToken));
            GamesROB.owners = GamesROB.dboAPI.get().getBot().getOwners().stream().map(Long::parseLong).collect(Collectors.toList());
    });

    private static final BootupTask presenceTask = args -> {
        Thread presenceThread = new Thread(() -> {
            while (true) {
                Log.wrapException("Updating presence", () -> {
                    Thread.sleep(Constants.PRESENCE_UPDATE_PERIOD);
                    GamesROB.setPresence();
                });
            }
        });
        presenceThread.setDaemon(true);
        presenceThread.setName("Presence updater thread");
        presenceThread.start();
        GamesROB.setPresence();

        // Twitch integration
        /*
        if (GamesROB.twitchUserIDListen != -1 && GamesROB.twitchClientID.isPresent()) {
            HttpRequest request = HttpRequest.post(String.format("https://api.twitch.tv/helix/webhooks/hub" +
                "?hub.callback=%s&hub.mode=%s&hub.topic=%s&hub.lease_seconds=%s",
                    "http%3A%2F%2F%2Ftwitchwebhook", "subscribe",
                    "https%3A%2F%2Fapi.twitch.tv%2Fhelix%2Fstreams%3Fuser_id%3D" + GamesROB.twitchUserIDListen,
                    864000))
                    .header("Accept", "application/vnd.twitchtv.v5+json")
                    .header("Client-ID", GamesROB.twitchClientID.get());
            Log.trace("Subscription to twitch response: " + request.body() + " (" + request.code() + ")");
        }
        */
    };

    private static final BootupTask loadWebsite = args -> {
        Website.start(port);
    };

    private static void task(List<String> args, String name, BootupTask task, boolean logTask) {
        if (logTask) GamesROB.shards.forEach(cur -> cur.getPresence().setGame(Game.watching("it all load... (" + name + ")")));

        Log.info(name + "...");
        long begin = System.currentTimeMillis();
        Log.wrapException("Failed to bootup on task: " + name, () -> task.execute(args));
        long time = System.currentTimeMillis() - begin;
        Log.info("Finished in " + Utility.formatPeriod(time));
    }
}
