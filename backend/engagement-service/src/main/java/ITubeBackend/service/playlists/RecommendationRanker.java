package ITubeBackend.service.playlists;

import ITubeBackend.dto.video.RecommendationCandidate;
import ITubeBackend.dto.video.VideoSummaryDto;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Deterministic content-based ranking, using only this user's signals. No external model or user tracking. */
public class RecommendationRanker {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{3,32}");
    private static final Set<String> STOP_WORDS = Set.of("the", "and", "with", "for", "from", "this", "that", "your", "how", "are", "you", "video", "videos", "official", "into", "why", "what");

    public record Seed(VideoSummaryDto video, double weight) { }
    public record Profile(Map<Long, Double> channels, Map<String, Double> topics, double usualSeconds) {
        public boolean personalized() { return !channels.isEmpty(); }
        public List<Long> preferredChannels() {
            return channels.entrySet().stream().sorted(Map.Entry.<Long, Double>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey())).limit(12).map(Map.Entry::getKey).toList();
        }
    }

    public Profile profile(List<Seed> seeds) {
        Map<Long, Double> channels = new HashMap<>();
        Map<String, Double> topics = new HashMap<>();
        double lengths = 0, totalWeight = 0;
        for (Seed seed : seeds) {
            VideoSummaryDto video = seed.video();
            if (!eligible(video) || seed.weight() <= 0) continue;
            channels.merge(video.channelId(), seed.weight(), Double::sum);
            words(video.title()).forEach(word -> topics.merge(word, seed.weight(), Double::sum));
            if (video.duration() != null && video.duration().toSecondOfDay() > 0) {
                lengths += Math.log(video.duration().toSecondOfDay()) * seed.weight();
                totalWeight += seed.weight();
            }
        }
        return new Profile(Map.copyOf(channels), Map.copyOf(topics), totalWeight == 0 ? 0 : Math.exp(lengths / totalWeight));
    }

    public List<Long> rank(List<RecommendationCandidate> candidates, Profile profile, Set<Long> excluded, Instant now, int limit) {
        Map<Long, RecommendationCandidate> unique = new LinkedHashMap<>();
        for (var candidate : candidates) {
            if (candidate != null && eligible(candidate.video()) && !excluded.contains(candidate.video().videoId())) {
                unique.putIfAbsent(candidate.video().videoId(), candidate);
            }
        }
        var ranked = unique.values().stream().sorted(Comparator
                .comparingDouble((RecommendationCandidate value) -> score(value, profile, now)).reversed()
                .thenComparing(value -> value.video().videoId(), Comparator.reverseOrder())).toList();
        LinkedHashSet<Long> selected = new LinkedHashSet<>();
        Map<Long, Integer> channelCounts = new HashMap<>();
        // Prefer variety, then fill remaining slots when the catalog only has a few channels.
        for (var candidate : ranked) {
            if (selected.size() == limit) break;
            long channel = candidate.video().channelId();
            if (channelCounts.getOrDefault(channel, 0) >= 3) continue;
            selected.add(candidate.video().videoId());
            channelCounts.merge(channel, 1, Integer::sum);
        }
        for (var candidate : ranked) {
            if (selected.size() == limit) break;
            selected.add(candidate.video().videoId());
        }
        return List.copyOf(selected);
    }

    public static boolean eligible(VideoSummaryDto video) {
        return video != null && video.videoId() != null && video.videoId() > 0 && video.channelId() != null && video.channelId() > 0
                && "PUBLIC".equals(video.visibility()) && "READY".equals(video.processingStatus())
                && video.videoUrl() != null;
    }

    private double score(RecommendationCandidate candidate, Profile profile, Instant now) {
        var video = candidate.video();
        double maxChannel = profile.channels().values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        double maxTopic = profile.topics().values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        double score = 5 * profile.channels().getOrDefault(video.channelId(), 0.0) / maxChannel;
        score += Math.min(3, words(video.title()).stream().mapToDouble(word -> profile.topics().getOrDefault(word, 0.0) / maxTopic).sum());
        if (profile.usualSeconds() > 0 && video.duration() != null && video.duration().toSecondOfDay() > 0) {
            score += 0.75 / (1 + Math.abs(Math.log(video.duration().toSecondOfDay() / profile.usualSeconds())));
        }
        double days = candidate.publishedAt() == null ? 3650 : Math.max(0, Duration.between(candidate.publishedAt(), now).toSeconds() / 86400.0);
        return score + 0.5 / (1 + days / 30);
    }

    private Set<String> words(String title) {
        if (title == null) return Set.of();
        Set<String> result = new HashSet<>();
        var matcher = WORD.matcher(title.toLowerCase(Locale.ROOT));
        while (matcher.find() && result.size() < 30) {
            String word = matcher.group();
            if (!STOP_WORDS.contains(word) && !word.codePoints().allMatch(Character::isDigit)) result.add(word);
        }
        return result;
    }
}
