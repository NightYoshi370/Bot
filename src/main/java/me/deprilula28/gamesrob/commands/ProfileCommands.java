package me.deprilula28.gamesrob .commands;

import com.github.kevinsawicki.http.HttpRequest;
import com.vdurmont.emoji.EmojiManager;
import javafx.util.Pair;
import lombok.AllArgsConstructor;
import lombok.Data;
import me.deprilula28.gamesrob.Language;
import me.deprilula28.gamesrob.data.GuildProfile;
import me.deprilula28.gamesrob.data.UserProfile;
import me.deprilula28.gamesrob.utility.Constants;
import me.deprilula28.gamesrob.utility.Log;
import me.deprilula28.gamesrob.utility.Utility;
import me.deprilula28.jdacmdframework.CommandContext;
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.entities.User;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ProfileCommands {
    private static Pattern emotePattern = Pattern.compile("(<:.*:[0-9]{18}>)");

    private static final int PROFILE_GAMES_BORDER = 30;
    private static final int PROFILE_COMMAND_WIDTH = ImageCommands.USER_PROFILE_WIDTH;
    private static final int PROFILE_COMMAND_TITLE_HEIGHT = 60;
    private static final int PROFILE_COMMAND_TITLE_FONT_SIZE = 40;
    private static final Color PROFILE_HEADER_COLOR = new Color(38, 50, 56, 200);

    private static final int TOKENS_DESCRIPTION_HEIGHT = 50;
    private static final int TOKENS_DESCRIPTION_FONT_SIZE = 35;
    private static final int TOKENS_CARD_HEIGHT = 30;
    private static final int TOKENS_CARD_FONT_SIZE = 20;
    private static final int TOKENS_CARD_TOKEN_AMOUNT_HEIGHT = TOKENS_CARD_HEIGHT + 10;
    private static final int TOKENS_CARD_TOKEN_AMOUNT_FONT_SIZE = 15;
    private static final int TOKENS_CARD_BORDERS = ImageCommands.LEADERBOARD_BORDERS + 10;
    private static final int TOKENS_SEPARATOR = 40;
    private static final String[] TOKEN_IMAGES = {
            "tokenimage0"
    };
    private static final List<Integer> TOKEN_IMAGE_MINIMUM = Arrays.asList(
            0
    );

    private static final int BADGES_PER_LINE = 8;
    private static final int BADGES_ICON_SIZE = 80;

    private static final double TOKEN_COMMAND_RATIO = 0.2;

    @AllArgsConstructor
    @Data
    private static class TokenGainMethod {
        private String translationCode;
        private State state;
        private Optional<Integer> gainAmount;

        @AllArgsConstructor
        private static enum State {
            TICK(new Color(0x00897B)), TICK_WEEKEND(new Color(0x76FF03)), X(new Color(0xF44336));

            private Color color;
        }
    }

    private static final List<Function<UserProfile, TokenGainMethod>> gainMethodFuncs = Arrays.asList(
            profile -> {
                long timeSinceUpvote = System.currentTimeMillis() - profile.getLastUpvote();
                if (timeSinceUpvote > TimeUnit.HOURS.toMillis(12)) {
                    int row = timeSinceUpvote < TimeUnit.DAYS.toMillis(2) ? profile.getUpvotedDays() : 0;
                    boolean weekend = Utility.isWeekendMultiplier();

                    return new TokenGainMethod("upvote", weekend
                            ? TokenGainMethod.State.TICK_WEEKEND : TokenGainMethod.State.TICK,
                            Optional.of((125 + row * 50) * (weekend ? 2 : 1)));
                } else return new TokenGainMethod("upvote", TokenGainMethod.State.X, Optional.empty());
            }, profile -> new TokenGainMethod("winMatches", TokenGainMethod.State.TICK, Optional.of(Constants.MATCH_WIN_TOKENS)),
            profile -> new TokenGainMethod("completeAchievements", TokenGainMethod.State.TICK, Optional.empty()),
            profile -> new TokenGainMethod("gambling", profile.getTokens() >= 50
                    ? TokenGainMethod.State.TICK : TokenGainMethod.State.X, Optional.empty())
    );

    public static Pair<Integer, Integer> profile(CommandContext context, Utility.Promise<CommandManager.RenderContext> rcontextPromise) {
        GuildProfile board = GuildProfile.get(context.getGuild());
        User user = context.opt(context::nextUser).orElseGet(context::getAuthor);
        UserProfile profile = UserProfile.get(user);

        final double LEADERBOARD_ENTRIES_RATIO = 1.0 - TOKEN_COMMAND_RATIO;
        final int LB_ENTRIES_WIDTH_ADJUSTED = (int) (PROFILE_COMMAND_WIDTH * LEADERBOARD_ENTRIES_RATIO);
        final int TOKENS_WIDTH_ADJUSTED = (int) (PROFILE_COMMAND_WIDTH * TOKEN_COMMAND_RATIO);
        final int TOKENS_IMAGE_SIZE = TOKENS_WIDTH_ADJUSTED - PROFILE_GAMES_BORDER;
        final int BADGES_WIDTH = PROFILE_COMMAND_WIDTH - PROFILE_GAMES_BORDER * 2;
        final int BADGES_OFFSET = (BADGES_WIDTH - BADGES_PER_LINE * BADGES_ICON_SIZE) / BADGES_PER_LINE;

        Set<Map.Entry<String, UserProfile.GameStatistics>> rawMap = board.getLeaderboard()
                .getStatsForUser(user.getId()).getRawMap().entrySet();
        List<TokenGainMethod> gainMethods = gainMethodFuncs.stream().map(it -> it.apply(profile)).collect(Collectors.toList());

        int badgesYInc = profile.getBadges().isEmpty() ? 0 :
                ((profile.getBadges().size() / BADGES_PER_LINE + 1) * BADGES_ICON_SIZE + PROFILE_GAMES_BORDER);

        rcontextPromise.then(rcontext -> {
            Graphics2D g2d = rcontext.getGraphics();
            String background = profile.getBackgroundImageUrl();

            int userprofileX = ImageCommands.drawBackground(g2d, background, PROFILE_COMMAND_WIDTH, ImageCommands.USER_PROFILE_HEIGHT,
                    rcontext.getHeight(), true);
            ImageCommands.drawUserProfile(context.getGuild().getMember(user), g2d, userprofileX, 0, rcontext.getStarlight());

            // Badges
            int badgesAmount = profile.getBadges().size();
            int filledLineWidth = BADGES_ICON_SIZE * badgesAmount + BADGES_OFFSET * (badgesAmount - 1);
            int lineStartX = BADGES_WIDTH / 2 - filledLineWidth / 2;
            for (int i = 0; i < badgesAmount; i++) {
                int x = PROFILE_GAMES_BORDER + lineStartX + BADGES_ICON_SIZE * i + BADGES_OFFSET * i;
                int y = ImageCommands.USER_PROFILE_HEIGHT + PROFILE_GAMES_BORDER / 2 +
                        (i / BADGES_PER_LINE) * BADGES_ICON_SIZE;

                UserProfile.Badge badge = profile.getBadges().get(i);
                g2d.drawImage(ImageCommands.getImage("badge_" + badge.getBadgeImageUrl(),
                        HttpRequest.get(badge.getBadgeImageUrl()).stream()), x, y, BADGES_ICON_SIZE, BADGES_ICON_SIZE,
                        null);
            }

            // Token Command
            int cury = PROFILE_COMMAND_TITLE_HEIGHT + ImageCommands.USER_PROFILE_HEIGHT + PROFILE_GAMES_BORDER + badgesYInc;
            String imageName = TOKEN_IMAGES[TOKEN_IMAGE_MINIMUM.stream().filter(it -> profile.getTokens() >= it)
                    .mapToInt(TOKEN_IMAGE_MINIMUM::indexOf).min().orElse(0)];
            Image image = ImageCommands.getImage(imageName, ImageCommands.class.getResourceAsStream("/imggen/" + imageName + ".png"));
            g2d.drawImage(image, PROFILE_GAMES_BORDER, cury, TOKENS_IMAGE_SIZE, TOKENS_IMAGE_SIZE, null);

            g2d.setColor(Color.white);
            g2d.setFont(rcontext.getStarlight().deriveFont((float) TOKENS_DESCRIPTION_FONT_SIZE).deriveFont(Font.PLAIN));
            ImageCommands.drawCenteredString(g2d, Utility.addNumberDelimitors(profile.getTokens()),
                    PROFILE_GAMES_BORDER, cury + TOKENS_IMAGE_SIZE + TOKENS_DESCRIPTION_HEIGHT / 2,
                    TOKENS_IMAGE_SIZE);

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle(Language.transl(context, "command.profile.tokenGuide"),
                            Constants.GAMESROB_DOMAIN + "/help/currency")
                    .setColor(Utility.getEmbedColor(context.getGuild()));

            int itemsy = 0;
            if (context.getAuthor().equals(user)){
                if (System.currentTimeMillis() - profile.getLastUpvote() > TimeUnit.HOURS.toMillis(12))
                    embed.setDescription(Language.transl(context, "command.profile.upvoteEmbed",
                            Constants.getDblVoteUrl(context.getJda(), "tokenscommand")));

                g2d.setFont(rcontext.getStarlight().deriveFont((float) TOKENS_CARD_FONT_SIZE).deriveFont(Font.PLAIN));
                int thingWidth = gainMethods.stream().mapToInt(it -> g2d.getFontMetrics()
                        .stringWidth(Language.transl(context, "command.profile." + it.getTranslationCode())
                                .replaceAll("%PREFIX%", Constants.getPrefix(context.getGuild())))).max().orElse(0);
                for (TokenGainMethod gainMethod : gainMethods) {
                    final int x = PROFILE_GAMES_BORDER + TOKENS_IMAGE_SIZE + TOKENS_SEPARATOR + TOKENS_CARD_BORDERS;
                    final int height = gainMethod.gainAmount.isPresent() ? TOKENS_CARD_TOKEN_AMOUNT_HEIGHT : TOKENS_CARD_HEIGHT;
                    g2d.setColor(gainMethod.state.color);
                    g2d.fillRect(PROFILE_GAMES_BORDER + TOKENS_IMAGE_SIZE + TOKENS_SEPARATOR, cury + itemsy - 10,
                            thingWidth + TOKENS_SEPARATOR * 2, height);
                    g2d.setColor(Color.white);

                    final int rendery = cury + itemsy + TOKENS_CARD_HEIGHT - TOKENS_CARD_BORDERS - g2d.getFontMetrics().getHeight() / 4 + 7;

                    g2d.drawString(Language.transl(context, "command.profile." + gainMethod.getTranslationCode())
                            .replaceAll("%PREFIX%", Constants.getPrefix(context.getGuild())), x, rendery);

                    final int renderyGainAmount = cury + itemsy + 10;
                    gainMethod.gainAmount.ifPresent(amount -> {
                        g2d.setFont(rcontext.getStarlight().deriveFont((float) TOKENS_CARD_TOKEN_AMOUNT_FONT_SIZE).deriveFont(Font.BOLD));
                        g2d.drawString(String.format("+ %s tokens", amount), x,
                                renderyGainAmount + height - TOKENS_CARD_BORDERS - g2d.getFontMetrics().getHeight() / 4);
                    });

                    itemsy += height;
                }
            }
            cury += Math.max(TOKENS_IMAGE_SIZE + TOKENS_DESCRIPTION_FONT_SIZE, itemsy);
            rcontext.getMessage().setEmbed(embed.build());

            // Leaderboard entries
            ImageCommands.drawLeaderboardEntry(Optional.of(PROFILE_HEADER_COLOR), Language.transl(context, "command.profile.game"),
                    g2d, PROFILE_GAMES_BORDER, cury, PROFILE_COMMAND_WIDTH - PROFILE_GAMES_BORDER * 2,
                    rcontext.getStarlight(), Constants.getLanguage(context),
                    () -> Language.transl(context, "command.profile.position"),
                    () -> Language.transl(context, "command.profile.victories"),
                    () -> Language.transl(context, "command.profile.losses"),
                    () -> Language.transl(context, "command.profile.gamesPlayed"),
                    () -> Language.transl(context, "command.profile.winPercent"));
            int curi = 1;
            for (Map.Entry<String, UserProfile.GameStatistics> entry : rawMap) {
                boolean overall = entry.getKey().equals("overall");
                ImageCommands.drawLeaderboardEntry(Optional.empty(), overall
                                ? Language.transl(context, "command.profile.overall")
                                : Language.transl(context, "game." + entry.getKey() + ".name"), entry.getValue(),
                        board.getIndex(board.getLeaderboard().getEntriesForGame(overall ? Optional.empty() :
                                Optional.of(entry.getKey())), user.getId()), g2d, PROFILE_GAMES_BORDER,
                        cury + ImageCommands.LEADERBOARD_ENTRY_HEIGHT * curi, PROFILE_COMMAND_WIDTH - PROFILE_GAMES_BORDER * 2,
                        rcontext.getStarlight(), Constants.getLanguage(context));
                curi ++;
            }

            g2d.setColor(Color.white);
            g2d.setFont(rcontext.getStarlight().deriveFont((float) PROFILE_COMMAND_TITLE_FONT_SIZE).deriveFont(Font.BOLD));
            ImageCommands.drawCenteredString(g2d, Language.transl(context, "command.profile.title"), 0, ImageCommands.USER_PROFILE_HEIGHT
                            + PROFILE_GAMES_BORDER + (PROFILE_COMMAND_TITLE_HEIGHT - PROFILE_COMMAND_TITLE_FONT_SIZE) / 2
                            + badgesYInc, PROFILE_COMMAND_WIDTH);
        });

        return new Pair<>(PROFILE_COMMAND_WIDTH, ImageCommands.LEADERBOARD_ENTRY_HEIGHT * (rawMap.size() + 1) +
                Math.max(TOKENS_IMAGE_SIZE + TOKENS_DESCRIPTION_HEIGHT, context.getAuthor().equals(user)
                        ? gainMethods.stream().mapToInt(it -> it.gainAmount.isPresent()
                        ? TOKENS_CARD_TOKEN_AMOUNT_HEIGHT : TOKENS_CARD_HEIGHT).sum() : 0) +
                + ImageCommands.USER_PROFILE_HEIGHT + PROFILE_GAMES_BORDER * 2 + PROFILE_COMMAND_TITLE_HEIGHT
                + badgesYInc);
    }

    public static String emojiTile(CommandContext context) {
        Optional<String> emoteOpt = context.opt(context::next);
        UserProfile profile = UserProfile.get(context.getAuthor());
        profile.setEdited(true);
        if (emoteOpt.isPresent()) {
            String emote = emoteOpt.get();
            if (!UserProfile.get(context.getAuthor()).transaction(150, "transactions.changingEmote"))
                return Constants.getNotEnoughTokensMessage(context, 150);

            if (EmojiManager.isEmoji(emote)) {
                profile.setEmote(emote);
                return Language.transl(context, "command.emote.set", emote);
            } else if (emotePattern.matcher(emote).matches()) {
                if (!validateEmote(context.getJda(), emote)) return Language.transl(context, "command.emote.cannotUse");

                profile.setEmote(emote);
                return Language.transl(context, "command.emote.set", emote);
            } else return Language.transl(context, "command.emote.invalid");
        } else {
            UserProfile.get(context.getAuthor()).setEmote(null);
            return Language.transl(context, "command.emote.reset");
        }
    }

    public static boolean validateEmote(JDA jda, String text) {
        return !emotePattern.matcher(text).matches() ||
                jda.getEmoteById(text.substring(text.length() - 19, text.length() - 1)) != null;
    }
}
