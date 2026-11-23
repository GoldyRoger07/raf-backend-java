package com.raikiservice.raf.practice.service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Progression du joueur : XP, niveau, missions du jour, badges et serie de jours.
 *
 * <p>Rien n'est stocke : tout se recalcule a partir des parties soumises (solo et groupe,
 * {@code game_sessions}) et des entrainements termines. Une partie invalidee ou expiree ne
 * rapporte rien. La progression ne rapporte <b>ni credit ni gourde</b> : elle motive sans
 * rien couter a RAF. Les missions se reussissent toutes en entrainement gratuit, pour que
 * jouer chaque jour n'oblige pas a payer.
 *
 * <p>Les jours suivent le calendrier d'Haiti. Les textes (missions, badges, titres) sont
 * dans l'app, par identifiant : ils se traduisent avec le reste.
 */
@Service
@RequiredArgsConstructor
public class ProgressService {

    private static final ZoneId HAITI = ZoneId.of("America/Port-au-Prince");

    private final JdbcTemplate jdbc;

    // --- Bareme d'XP -------------------------------------------------------------
    public static final int XP_GAME = 20;
    public static final int XP_CORRECT = 10;
    public static final int XP_PERFECT = 50;
    public static final int XP_PRACTICE = 10;
    public static final int XP_PRACTICE_CORRECT = 5;
    /** Bonus quand toutes les missions du jour sont faites. */
    public static final int XP_ALL_MISSIONS = 30;

    /** Missions du jour, les memes chaque jour. */
    public enum Mission {
        /** Terminer une partie ou un entrainement. */
        PLAY_ONE(1, 15),
        /** Trouver 10 bonnes reponses dans la journee. */
        CORRECT_TEN(10, 25),
        /** Jouer sur 2 sujets differents. */
        TWO_SUBJECTS(2, 30);

        public final int target;
        public final int xp;

        Mission(int target, int xp) {
            this.target = target;
            this.xp = xp;
        }
    }

    /** Badges : un seuil a atteindre sur un compteur. */
    public enum Badge {
        FIRST_GAME(Counter.GAMES, 1),
        PRACTICE_5(Counter.PRACTICES, 5),
        GAMES_10(Counter.GAMES, 10),
        GAMES_50(Counter.GAMES, 50),
        GAMES_200(Counter.GAMES, 200),
        CORRECT_100(Counter.CORRECT, 100),
        CORRECT_1000(Counter.CORRECT, 1000),
        STREAK_3(Counter.BEST_STREAK, 3),
        STREAK_7(Counter.BEST_STREAK, 7),
        STREAK_30(Counter.BEST_STREAK, 30),
        SUBJECTS_5(Counter.SUBJECTS, 5),
        PERFECT(Counter.PERFECTS, 1),
        PODIUM(Counter.PODIUMS, 1),
        LEVEL_10(Counter.LEVEL, 10),
        LEVEL_25(Counter.LEVEL, 25);

        public final Counter counter;
        public final int target;

        Badge(Counter counter, int target) {
            this.counter = counter;
            this.target = target;
        }
    }

    public enum Counter {
        GAMES, PRACTICES, CORRECT, BEST_STREAK, SUBJECTS, PERFECTS, PODIUMS, LEVEL
    }

    /** Titres par palier de niveau (premier niveau de chaque titre). */
    public enum Title {
        BEGINNER(1), APPRENTICE(5), CONNOISSEUR(10), EXPERT(20), MASTER(35), LEGEND(50);

        public final int fromLevel;

        Title(int fromLevel) {
            this.fromLevel = fromLevel;
        }

        static Title of(int level) {
            Title result = BEGINNER;
            for (Title title : values()) {
                if (level >= title.fromLevel) {
                    result = title;
                }
            }
            return result;
        }
    }

    public record MissionView(Mission id, int progress, int target, int xp, boolean done) {
    }

    public record BadgeView(Badge id, long progress, int target, boolean achieved) {
    }

    public record Progress(
            long totalXp,
            int level,
            Title title,
            /** XP gagnee dans le niveau en cours, et ce qu'il faut pour le suivant. */
            long levelXp,
            long levelXpNeeded,
            long todayXp,
            int currentStreakDays,
            int bestStreakDays,
            boolean playedToday,
            List<MissionView> missions,
            int allMissionsBonusXp,
            boolean allMissionsDone,
            List<BadgeView> badges) {
    }

    /** XP pour passer du niveau {@code level} au suivant : 100, puis 25 de plus a chaque niveau. */
    public static long xpForNextLevel(int level) {
        return 100L + 25L * (level - 1);
    }

    private record Activity(LocalDate day, long categoryId, int correct, int xp) {
    }

    private static final class Day {
        int played;
        int correct;
        final Set<Long> subjects = new HashSet<>();
        long xp;
    }

    @Transactional(readOnly = true)
    public Progress of(Long userId) {
        List<Activity> activities = new ArrayList<>();
        long[] totals = new long[3]; // parties, bonnes reponses, sans-faute
        jdbc.query("""
                SELECT gs.started_at, COALESCE(gs.category_id, q.selected_category_id, 0) AS category_id,
                       COALESCE(gs.correct_count, 0) AS correct, gs.game_mode = 'SOLO' AND gs.perfect AS perfect
                FROM game_sessions gs
                JOIN quizzes q ON q.id = gs.quiz_id
                WHERE gs.user_id = ? AND gs.status = 'SUBMITTED'
                """, rs -> {
                    int correct = rs.getInt("correct");
                    boolean perfect = rs.getBoolean("perfect");
                    totals[0]++;
                    totals[1] += correct;
                    if (perfect) {
                        totals[2]++;
                    }
                    activities.add(new Activity(day(rs.getTimestamp("started_at")), rs.getLong("category_id"),
                            correct, XP_GAME + XP_CORRECT * correct + (perfect ? XP_PERFECT : 0)));
                }, userId);
        long[] practice = new long[2]; // entrainements, bonnes reponses
        jdbc.query("""
                SELECT started_at, category_id, correct_count FROM practice_sessions
                WHERE user_id = ? AND finished_at IS NOT NULL
                """, rs -> {
                    int correct = rs.getInt("correct_count");
                    practice[0]++;
                    practice[1] += correct;
                    activities.add(new Activity(day(rs.getTimestamp("started_at")), rs.getLong("category_id"),
                            correct, XP_PRACTICE + XP_PRACTICE_CORRECT * correct));
                }, userId);
        Long podiums = jdbc.queryForObject("SELECT COUNT(*) FROM quiz_rankings WHERE user_id = ? AND rank_position <= 3",
                Long.class, userId);

        Map<LocalDate, Day> days = new HashMap<>();
        Set<Long> subjects = new HashSet<>();
        for (Activity activity : activities) {
            Day day = days.computeIfAbsent(activity.day(), d -> new Day());
            day.played++;
            day.correct += activity.correct();
            day.xp += activity.xp();
            if (activity.categoryId() != 0) {
                day.subjects.add(activity.categoryId());
                subjects.add(activity.categoryId());
            }
        }
        long totalXp = 0;
        for (Day day : days.values()) {
            day.xp += missionXp(day);
            totalXp += day.xp;
        }

        LocalDate today = LocalDate.now(HAITI);
        Day todayActivity = days.getOrDefault(today, new Day());
        List<MissionView> missions = new ArrayList<>();
        for (Mission mission : Mission.values()) {
            int progress = Math.min(mission.target, progress(mission, todayActivity));
            missions.add(new MissionView(mission, progress, mission.target, mission.xp, progress >= mission.target));
        }
        boolean allDone = missions.stream().allMatch(MissionView::done);

        TreeSet<LocalDate> played = new TreeSet<>(days.keySet());
        List<LocalDate> desc = List.copyOf(played.descendingSet());
        int current = currentStreak(desc, today);
        int best = bestStreak(desc);

        int level = 1;
        long remaining = totalXp;
        while (remaining >= xpForNextLevel(level)) {
            remaining -= xpForNextLevel(level);
            level++;
        }

        Map<Counter, Long> counters = new HashMap<>();
        counters.put(Counter.GAMES, totals[0] + practice[0]);
        counters.put(Counter.PRACTICES, practice[0]);
        counters.put(Counter.CORRECT, totals[1] + practice[1]);
        counters.put(Counter.BEST_STREAK, (long) best);
        counters.put(Counter.SUBJECTS, (long) subjects.size());
        counters.put(Counter.PERFECTS, totals[2]);
        counters.put(Counter.PODIUMS, podiums == null ? 0 : podiums);
        counters.put(Counter.LEVEL, (long) level);
        List<BadgeView> badges = new ArrayList<>();
        for (Badge badge : Badge.values()) {
            long value = counters.get(badge.counter);
            badges.add(new BadgeView(badge, Math.min(value, badge.target), badge.target, value >= badge.target));
        }

        return new Progress(totalXp, level, Title.of(level), remaining, xpForNextLevel(level),
                todayActivity.xp, current, best, todayActivity.played > 0, missions, XP_ALL_MISSIONS, allDone,
                badges);
    }

    private static LocalDate day(Timestamp at) {
        return at.toInstant().atZone(HAITI).toLocalDate();
    }

    private static int progress(Mission mission, Day day) {
        return switch (mission) {
            case PLAY_ONE -> day.played;
            case CORRECT_TEN -> day.correct;
            case TWO_SUBJECTS -> day.subjects.size();
        };
    }

    /** XP des missions faites ce jour-la, bonus compris si toutes le sont. */
    static long missionXp(Day day) {
        long xp = 0;
        boolean all = true;
        for (Mission mission : Mission.values()) {
            if (progress(mission, day) >= mission.target) {
                xp += mission.xp;
            } else {
                all = false;
            }
        }
        return all ? xp + XP_ALL_MISSIONS : xp;
    }

    /** Jours consecutifs joues jusqu'a aujourd'hui, ou hier si rien encore aujourd'hui. */
    static int currentStreak(List<LocalDate> daysDesc, LocalDate today) {
        if (daysDesc.isEmpty()) {
            return 0;
        }
        LocalDate expected = daysDesc.get(0).equals(today) ? today : today.minusDays(1);
        int streak = 0;
        for (LocalDate day : daysDesc) {
            if (!day.equals(expected)) {
                break;
            }
            streak++;
            expected = expected.minusDays(1);
        }
        return streak;
    }

    static int bestStreak(List<LocalDate> daysDesc) {
        int best = 0;
        int run = 0;
        LocalDate previous = null;
        for (LocalDate day : daysDesc) {
            run = previous != null && day.equals(previous.minusDays(1)) ? run + 1 : 1;
            best = Math.max(best, run);
            previous = day;
        }
        return best;
    }
}
